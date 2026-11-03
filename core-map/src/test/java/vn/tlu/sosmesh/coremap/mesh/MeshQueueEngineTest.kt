package vn.tlu.sosmesh.coremap.mesh

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.tlu.sosmesh.coremap.alert.AlertSignatureVerifier
import vn.tlu.sosmesh.coremap.db.MeshMessageEntity
import java.util.Base64

class MeshQueueEngineTest {

    private fun samplePacket(
        id: String,
        type: String = "SOS",
        urgency: String = "HIGH",
        ttl: Int = 4,
        hopCount: Int = 0,
        status: String = "QUEUED",
        signature: String? = null,
        createdAt: Long = 1_000_000L
    ) = MeshMessageEntity(
        id = id,
        type = type,
        senderId = "NODE-01",
        senderName = "Test Node",
        latitude = 21.0075,
        longitude = 105.8342,
        peopleCount = 3,
        conditionSummary = "Test summary",
        urgencyLevel = urgency,
        content = "Test content",
        signature = signature,
        ttl = ttl,
        hopCount = hopCount,
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    @Test
    fun `deduplication drops duplicate packet IDs but allows higher status update`() {
        val engine = MeshQueueEngine()
        val p1 = samplePacket(id = "SOS-01", status = "QUEUED")
        val dup = samplePacket(id = "SOS-01", status = "QUEUED")
        val approachingUpdate = samplePacket(id = "SOS-01", status = "APPROACHING")

        assertTrue(engine.enqueueOrUpdate(p1, 1_000_000L) is EnqueueResult.Enqueued)
        assertEquals(EnqueueResult.DuplicateDropped, engine.enqueueOrUpdate(dup, 1_000_100L))
        val updated = engine.enqueueOrUpdate(approachingUpdate, 1_000_200L)
        assertTrue(updated is EnqueueResult.UpdatedStatus)
        assertEquals("APPROACHING", engine.prioritizedQueue().first().status)
    }

    @Test
    fun `priority queue orders CRITICAL SOS before HIGH SOS before signed ALERT before CHAT`() {
        AlertSignatureVerifier.resetTrustStoreToDefaults()
        val testOfficialKeyPair = AlertSignatureVerifier.generateKeyPair()
        val testOfficialPubB64 = Base64.getEncoder().encodeToString(testOfficialKeyPair.public.encoded)
        AlertSignatureVerifier.addTrustedPublicKeyForTest(testOfficialPubB64)

        val engine = MeshQueueEngine()
        val chat = samplePacket(id = "CHAT-1", type = "CHAT", urgency = "MEDIUM", createdAt = 100L)
        val unsignedAlert = samplePacket(
            id = "ALERT-1",
            type = "ALERT",
            urgency = "CRITICAL",
            createdAt = 200L
        )
        val signedAlert = AlertSignatureVerifier.signSignedAlertPacket(unsignedAlert, testOfficialKeyPair.private)
        val highSos = samplePacket(id = "SOS-HIGH", type = "SOS", urgency = "HIGH", createdAt = 300L)
        val critSos = samplePacket(id = "SOS-CRIT", type = "SOS", urgency = "CRITICAL", createdAt = 400L)

        engine.enqueueOrUpdate(chat, 1000L)
        engine.enqueueOrUpdate(signedAlert, 1000L)
        engine.enqueueOrUpdate(highSos, 1000L)
        engine.enqueueOrUpdate(critSos, 1000L)

        val orderedIds = engine.prioritizedQueue().map { it.id }
        assertEquals(listOf("SOS-CRIT", "SOS-HIGH", "ALERT-1", "CHAT-1"), orderedIds)
        AlertSignatureVerifier.resetTrustStoreToDefaults()
    }

    @Test
    fun `ECDSA digital signature accepts trusted official key and rejects untrusted key or prefix spoof`() {
        AlertSignatureVerifier.resetTrustStoreToDefaults()
        val trustedKeyPair = AlertSignatureVerifier.generateKeyPair()
        val trustedPubB64 = Base64.getEncoder().encodeToString(trustedKeyPair.public.encoded)
        AlertSignatureVerifier.addTrustedPublicKeyForTest(trustedPubB64)

        val engine = MeshQueueEngine()
        val baseAlert = samplePacket(id = "ALERT-REAL", type = "ALERT", urgency = "CRITICAL")
        val signedAlert = AlertSignatureVerifier.signSignedAlertPacket(baseAlert, trustedKeyPair.private)

        assertTrue(AlertSignatureVerifier.verifyAlert(signedAlert))
        assertTrue(engine.enqueueOrUpdate(signedAlert, 1_000_000L) is EnqueueResult.Enqueued)

        val legacyPrefixSpoof = samplePacket(
            id = "ALERT-SPOOF",
            type = "ALERT",
            signature = "SIG-TLU-OFFICIAL-12345"
        )
        assertFalse(AlertSignatureVerifier.verifyAlert(legacyPrefixSpoof))
        assertEquals(
            EnqueueResult.DroppedInvalidSignature,
            engine.enqueueOrUpdate(legacyPrefixSpoof, 1_000_000L)
        )

        val tamperedAlert = signedAlert.copy(id = "ALERT-TAMPERED", content = "Nội dung giả mạo")
        assertFalse(AlertSignatureVerifier.verifyAlert(tamperedAlert))
        assertEquals(
            EnqueueResult.DroppedInvalidSignature,
            engine.enqueueOrUpdate(tamperedAlert, 1_000_000L)
        )

        val untrustedKeyPair = AlertSignatureVerifier.generateKeyPair()
        val untrustedSigned = AlertSignatureVerifier.signSignedAlertPacket(
            samplePacket(id = "ALERT-ROGUE", type = "ALERT"),
            untrustedKeyPair.private
        )
        assertFalse(AlertSignatureVerifier.verifyAlert(untrustedSigned))
        assertEquals(
            EnqueueResult.DroppedInvalidSignature,
            engine.enqueueOrUpdate(untrustedSigned, 1_000_000L)
        )
        AlertSignatureVerifier.resetTrustStoreToDefaults()
    }

    @Test
    fun `prepareForForward decrements TTL and increments hopCount until zero`() {
        val engine = MeshQueueEngine()
        val hop0 = samplePacket(id = "SOS-TTL", ttl = 2, hopCount = 0)
        val hop1 = engine.prepareForForward(hop0)
        assertNotNull(hop1)
        assertEquals(1, hop1!!.ttl)
        assertEquals(1, hop1.hopCount)

        val hop2 = engine.prepareForForward(hop1)
        assertNull(hop2)
    }

    @Test
    fun `summary vector exchange returns only packets missing on peer`() {
        val engine = MeshQueueEngine()
        engine.enqueueOrUpdate(samplePacket(id = "SOS-1"), 1_000_000L)
        engine.enqueueOrUpdate(samplePacket(id = "SOS-2"), 1_000_000L)
        engine.enqueueOrUpdate(samplePacket(id = "SOS-3"), 1_000_000L)

        val peerSummary = setOf("SOS-1", "SOS-3")
        val missing = engine.computeMissingForPeer(peerSummary)
        assertEquals(listOf("SOS-2"), missing.map { it.id })
    }

    @Test
    fun `packet codec serializes id type origin createdAt ttl hops and round-trips cleanly`() {
        val original = samplePacket(
            id = "SOS-CODEC-01",
            type = "SOS",
            urgency = "CRITICAL",
            ttl = 3,
            hopCount = 2,
            createdAt = 1_762_000_000_000L
        )
        val encoded = MeshPacketCodec.encodePacket(original)
        val rawJson = JSONObject(encoded.toString(Charsets.UTF_8))

        assertEquals("SOS-CODEC-01", rawJson.getString("id"))
        assertEquals("SOS", rawJson.getString("type"))
        assertEquals("NODE-01", rawJson.getString("origin"))
        assertEquals(1_762_000_000_000L, rawJson.getLong("createdAt"))
        assertEquals(3, rawJson.getInt("ttl"))
        assertEquals(2, rawJson.getInt("hops"))

        val decoded = MeshPacketCodec.decodeFrame(encoded)
        assertTrue(decoded is DecodedMeshFrame.Packet)
        val msg = (decoded as DecodedMeshFrame.Packet).message
        assertEquals(original.id, msg.id)
        assertEquals(original.type, msg.type)
        assertEquals(original.senderId, msg.senderId)
        assertEquals(original.createdAt, msg.createdAt)
        assertEquals(original.ttl, msg.ttl)
        assertEquals(original.hopCount, msg.hopCount)
    }

    @Test
    fun `ACK_STATUS packet updates target SOS status in queue`() {
        val engine = MeshQueueEngine()
        val sos = samplePacket(id = "SOS-TARGET-01", type = "SOS", status = "QUEUED")
        engine.enqueueOrUpdate(sos, 1_000_000L)

        val ackApproaching = samplePacket(
            id = "ACK-SOS-TARGET-01-APPROACHING",
            type = "ACK_STATUS",
            status = "APPROACHING",
            createdAt = 1_005_000L
        ).copy(content = "SOS-TARGET-01|APPROACHING")

        engine.enqueueOrUpdate(ackApproaching, 1_005_000L)
        val updatedSos = engine.prioritizedQueue().first { it.id == "SOS-TARGET-01" }
        assertEquals("APPROACHING", updatedSos.status)
    }

    @Test
    fun `firebase gateway deduplicates synced IDs and verifies cloud ALERT signatures`() {
        val p1 = samplePacket(id = "SOS-SYNC-1")
        val p2 = samplePacket(id = "SOS-SYNC-2")
        val unsynced = vn.tlu.sosmesh.coremap.cloud.FirebaseMeshGateway.filterUnsyncedForUpload(
            listOf(p1, p2),
            setOf("SOS-SYNC-1")
        )
        assertEquals(listOf("SOS-SYNC-2"), unsynced.map { it.id })

        AlertSignatureVerifier.resetTrustStoreToDefaults()
        val cloudOfficialKeyPair = AlertSignatureVerifier.generateKeyPair()
        AlertSignatureVerifier.addTrustedPublicKeyForTest(
            Base64.getEncoder().encodeToString(cloudOfficialKeyPair.public.encoded)
        )
        val validAlert = AlertSignatureVerifier.signSignedAlertPacket(
            samplePacket(id = "ALERT-CLOUD-1", type = "ALERT", urgency = "CRITICAL"),
            cloudOfficialKeyPair.private
        )
        assertTrue(
            vn.tlu.sosmesh.coremap.cloud.FirebaseMeshGateway.shouldAcceptPulledAlert(
                alert = validAlert,
                localSummaryVector = emptySet(),
                alreadySyncedIds = emptySet()
            )
        )
        assertFalse(
            vn.tlu.sosmesh.coremap.cloud.FirebaseMeshGateway.shouldAcceptPulledAlert(
                alert = validAlert,
                localSummaryVector = setOf("ALERT-CLOUD-1"),
                alreadySyncedIds = emptySet()
            )
        )
    }

    @Test
    fun `rescue list sorts SOS by urgency first then Haversine distance`() {
        val farCritical = samplePacket(id = "SOS-CRIT-FAR", urgency = "CRITICAL").copy(
            latitude = 21.0500,
            longitude = 105.8500
        )
        val nearCritical = samplePacket(id = "SOS-CRIT-NEAR", urgency = "CRITICAL").copy(
            latitude = 21.0080,
            longitude = 105.8345
        )
        val veryNearHigh = samplePacket(id = "SOS-HIGH-VERYNEAR", urgency = "HIGH").copy(
            latitude = 21.0076,
            longitude = 105.8343
        )
        val sorted = vn.tlu.sosmesh.coremap.ui.MeshFeatureDialogs.sortSosForRescue(
            messages = listOf(farCritical, veryNearHigh, nearCritical),
            rescueLat = 21.0075,
            rescueLon = 105.8342
        )
        assertEquals(
            listOf("SOS-CRIT-NEAR", "SOS-CRIT-FAR", "SOS-HIGH-VERYNEAR"),
            sorted.map { it.id }
        )
    }

    @Test
    fun `SOS status progression formats QUEUED DELIVERED APPROACHING REACHED`() {
        val text = vn.tlu.sosmesh.coremap.ui.MeshFeatureDialogs.formatSosStatusProgression("APPROACHING")
        assertEquals("[QUEUED] → [DELIVERED] → [APPROACHING] → REACHED", text)
    }

    @Test
    fun `summary vector codec round-trips node metadata and packet IDs`() {
        val bytes = MeshPacketCodec.encodeSummaryVector(
            nodeId = "NODE-A1",
            deviceName = "Xiaomi Test",
            androidApi = 35,
            packetIds = setOf("SOS-1", "ALERT-2", "CHAT-3")
        )
        val decoded = MeshPacketCodec.decodeFrame(bytes)
        assertTrue(decoded is DecodedMeshFrame.SummaryVector)
        val sv = decoded as DecodedMeshFrame.SummaryVector
        assertEquals("NODE-A1", sv.nodeId)
        assertEquals("Xiaomi Test", sv.deviceName)
        assertEquals(35, sv.androidApi)
        assertEquals(setOf("SOS-1", "ALERT-2", "CHAT-3"), sv.packetIds)
    }

    @Test
    fun `multi-hop 4-node simulation forwards SOS and propagates ACK_STATUS back to sender`() {
        val nodeA = MeshQueueEngine()
        val nodeB = MeshQueueEngine()
        val nodeC = MeshQueueEngine()
        val nodeRescue = MeshQueueEngine()

        val t0 = 1_762_000_000_000L
        val sosOrigin = samplePacket(
            id = "SOS-MULTIHOP-01",
            type = "SOS",
            urgency = "CRITICAL",
            ttl = 4,
            hopCount = 0,
            status = "QUEUED",
            createdAt = t0
        )
        nodeA.enqueueOrUpdate(sosOrigin, t0)

        // Hop 1: Node A -> Node B via summary vector diff
        val missingForB = nodeA.computeMissingForPeer(nodeB.summaryVector())
        assertEquals(1, missingForB.size)
        val hop1Packet = nodeA.prepareForForward(missingForB.first())!!
        assertEquals(1, hop1Packet.hopCount)
        assertEquals(3, hop1Packet.ttl)
        nodeB.enqueueOrUpdate(hop1Packet, t0 + 450L)

        // Hop 2: Node B -> Node C
        val missingForC = nodeB.computeMissingForPeer(nodeC.summaryVector())
        val hop2Packet = nodeB.prepareForForward(missingForC.first())!!
        assertEquals(2, hop2Packet.hopCount)
        assertEquals(2, hop2Packet.ttl)
        nodeC.enqueueOrUpdate(hop2Packet, t0 + 980L)

        // Hop 3: Node C -> Rescue Node
        val missingForRescue = nodeC.computeMissingForPeer(nodeRescue.summaryVector())
        val hop3Packet = nodeC.prepareForForward(missingForRescue.first())!!
        val receivedAtRescue = t0 + 1_620L
        assertEquals(3, hop3Packet.hopCount)
        assertEquals(1, hop3Packet.ttl)
        assertEquals(1_620L, receivedAtRescue - hop3Packet.createdAt)
        nodeRescue.enqueueOrUpdate(hop3Packet, receivedAtRescue)

        // Rescue Node dispatches APPROACHING ACK_STATUS back across C -> B -> A
        val ackPacket = vn.tlu.sosmesh.coremap.ui.MeshFeatureDialogs.buildAckStatusPacket(
            targetSos = hop3Packet,
            newStatus = "APPROACHING",
            responderNodeId = "NODE-RESCUE-01",
            responderName = "Đội Cứu hộ 1",
            responderLat = 21.0080,
            responderLon = 105.8345,
            nowMs = receivedAtRescue + 200L
        )
        nodeRescue.enqueueOrUpdate(ackPacket, receivedAtRescue + 200L)
        nodeC.enqueueOrUpdate(nodeRescue.prepareForForward(ackPacket)!!, receivedAtRescue + 500L)
        nodeB.enqueueOrUpdate(nodeC.prepareForForward(nodeRescue.prepareForForward(ackPacket)!!)!!, receivedAtRescue + 800L)
        val ackAtA = nodeB.prepareForForward(nodeC.prepareForForward(nodeRescue.prepareForForward(ackPacket)!!)!!)!!
        nodeA.enqueueOrUpdate(ackAtA, receivedAtRescue + 1_100L)

        val sosAtOrigin = nodeA.prioritizedQueue().first { it.id == "SOS-MULTIHOP-01" }
        assertEquals("APPROACHING", sosAtOrigin.status)
    }

    @Test
    fun `battery scan controller selects adaptive profile and queue purges expired TTL`() {
        val activeSosProfile = vn.tlu.sosmesh.coremap.power.BatteryScanController.selectAdaptiveProfile(
            batteryPct = 80,
            userBatterySaverEnabled = false,
            hasActiveCriticalSos = true
        )
        assertEquals(5_000L, activeSosProfile.scanIntervalMs)

        val saverProfile = vn.tlu.sosmesh.coremap.power.BatteryScanController.selectAdaptiveProfile(
            batteryPct = 20,
            userBatterySaverEnabled = false,
            hasActiveCriticalSos = false
        )
        assertEquals(45_000L, saverProfile.scanIntervalMs)

        val balancedProfile = vn.tlu.sosmesh.coremap.power.BatteryScanController.selectAdaptiveProfile(
            batteryPct = 75,
            userBatterySaverEnabled = false,
            hasActiveCriticalSos = false
        )
        assertEquals(15_000L, balancedProfile.scanIntervalMs)

        val engine = MeshQueueEngine(maxAgeMs = 10_000L)
        engine.enqueueOrUpdate(samplePacket(id = "SOS-OLD", createdAt = 1_000L), 2_000L)
        engine.enqueueOrUpdate(samplePacket(id = "SOS-FRESH", createdAt = 15_000L), 15_500L)
        val purged = engine.purgeExpired(nowMs = 16_000L)
        assertEquals(1, purged)
        assertEquals(listOf("SOS-FRESH"), engine.prioritizedQueue().map { it.id })
    }
}
