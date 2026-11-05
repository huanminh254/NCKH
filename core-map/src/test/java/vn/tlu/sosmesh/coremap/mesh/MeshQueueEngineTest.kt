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
    fun `prepareForForward increments hopCount without hop limit`() {
        val engine = MeshQueueEngine()
        var current = samplePacket(id = "SOS-UNLIMITED-HOPS", ttl = MeshQueueEngine.UNLIMITED_TTL, hopCount = 0)
        for (expectedHop in 1..25) {
            current = engine.prepareForForward(current)
            assertNotNull(current)
            assertEquals(expectedHop, current.hopCount)
        }
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
        val hop1Packet = nodeA.prepareForForward(missingForB.first())
        assertEquals(1, hop1Packet.hopCount)
        nodeB.enqueueOrUpdate(hop1Packet, t0 + 450L)

        // Hop 2: Node B -> Node C
        val missingForC = nodeB.computeMissingForPeer(nodeC.summaryVector())
        val hop2Packet = nodeB.prepareForForward(missingForC.first())
        assertEquals(2, hop2Packet.hopCount)
        nodeC.enqueueOrUpdate(hop2Packet, t0 + 980L)

        // Hop 3: Node C -> Rescue Node
        val missingForRescue = nodeC.computeMissingForPeer(nodeRescue.summaryVector())
        val hop3Packet = nodeC.prepareForForward(missingForRescue.first())
        val receivedAtRescue = t0 + 1_620L
        assertEquals(3, hop3Packet.hopCount)
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
        nodeC.enqueueOrUpdate(nodeRescue.prepareForForward(ackPacket), receivedAtRescue + 500L)
        nodeB.enqueueOrUpdate(nodeC.prepareForForward(nodeRescue.prepareForForward(ackPacket)), receivedAtRescue + 800L)
        val ackAtA = nodeB.prepareForForward(nodeC.prepareForForward(nodeRescue.prepareForForward(ackPacket)))
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

    @Test
    fun `finite TTL counts every link and leaf retains SOS without forwarding`() {
        val a = MeshQueueEngine()
        val b = MeshQueueEngine()
        val c = MeshQueueEngine()
        val packet = samplePacket("FINITE", ttl = 2)
        a.enqueueOrUpdate(packet, 1_000_000L)
        val hop1 = a.prepareForForward(packet)
        assertEquals(1, hop1.ttl)
        b.enqueueOrUpdate(hop1, 1_000_001L)
        val hop2 = b.prepareForForward(hop1)
        assertEquals(0, hop2.ttl)
        assertEquals(2, hop2.hopCount)
        assertTrue(c.enqueueOrUpdate(hop2, 1_000_002L) is EnqueueResult.Enqueued)
        assertEquals(1, c.size())
        assertTrue(c.computeMissingForPeer(emptySet()).isEmpty())
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { c.prepareForForward(hop2) }
    }

    @Test
    fun `seen cache stays bounded while queue retains urgent packets`() {
        val engine = MeshQueueEngine(maxQueueSize = 2, maxSeenPackets = 4)
        engine.enqueueOrUpdate(samplePacket("URGENT", urgency = "CRITICAL"), 1_000_000L)
        for (i in 1..100) engine.enqueueOrUpdate(samplePacket("CHAT-$i", type = "CHAT"), 1_000_000L)
        assertEquals(2, engine.size())
        assertTrue(engine.seenCount() <= 4)
        assertTrue(engine.summaryVector().contains("URGENT"))
        assertEquals(engine.prioritizedQueue().map { it.id }.toSet(), engine.summaryVector())
    }

    @Test
    fun `expired tombstones are purged with messages at exact age boundary`() {
        val engine = MeshQueueEngine(maxAgeMs = 1000L)
        engine.enqueueOrUpdate(samplePacket("OLD", createdAt = 1000L), 1000L)
        assertEquals(1, engine.purgeExpired(2000L))
        assertEquals(0, engine.seenCount())
        assertTrue(engine.summaryVector().isEmpty())
        assertEquals(EnqueueResult.DroppedStale,
            engine.enqueueOrUpdate(samplePacket("OLD", createdAt = 1000L), 2000L))
    }

    @Test
    fun `summary versions send higher status for ID peer already has`() {
        val sender = MeshQueueEngine()
        val receiver = MeshQueueEngine()
        val packet = samplePacket("SAME-ID")
        sender.enqueueOrUpdate(packet.copy(status = "REACHED"), 1_000_000L)
        receiver.enqueueOrUpdate(packet, 1_000_000L)
        val missing = sender.computeMissingForPeer(receiver.summaryVector(), receiver.summaryStatusRanks())
        assertEquals(listOf("SAME-ID"), missing.map { it.id })
        receiver.enqueueOrUpdate(sender.prepareForForward(missing.single()), 1_000_001L)
        assertEquals("REACHED", receiver.prioritizedQueue().single().status)
        assertTrue(sender.computeMissingForPeer(receiver.summaryVector(), receiver.summaryStatusRanks()).isEmpty())
    }

    @Test
    fun `delayed ACK does not regress reached and malformed ACK is rejected`() {
        val engine = MeshQueueEngine()
        engine.enqueueOrUpdate(samplePacket("SOS", status = "REACHED"), 1_000_000L)
        val ack = samplePacket("ACK", type = "ACK_STATUS", status = "DELIVERED")
            .copy(content = "SOS|DELIVERED")
        engine.enqueueOrUpdate(ack, 1_000_001L)
        assertEquals("REACHED", engine.prioritizedQueue().first { it.id == "SOS" }.status)
        assertEquals(EnqueueResult.DroppedInvalidPacket,
            engine.enqueueOrUpdate(ack.copy(id = "INVALID", content = "SOS|INVALID"), 1_000_002L))
    }

    @Test
    fun `ACK arriving before SOS upgrades later SOS`() {
        val engine = MeshQueueEngine()
        engine.enqueueOrUpdate(samplePacket("ACK", type = "ACK_STATUS", status = "APPROACHING")
            .copy(content = "SOS-LATE|APPROACHING"), 1_000_000L)
        val result = engine.enqueueOrUpdate(samplePacket("SOS-LATE"), 1_000_001L)
        assertEquals("APPROACHING", (result as EnqueueResult.Enqueued).packet.status)
    }

    @Test
    fun `store carry restart resumes pending SOS and received status`() {
        val firstSession = MeshQueueEngine()
        val p = samplePacket("PERSISTED", status = "DELIVERED")
        firstSession.enqueueOrUpdate(p, 1_000_000L)
        val persisted = firstSession.prioritizedQueue()
        val restarted = MeshQueueEngine()
        persisted.forEach { restarted.enqueueOrUpdate(it, 1_000_100L) }
        val rescue = MeshQueueEngine()
        val outgoing = restarted.computeMissingForPeer(rescue.summaryVector()).single()
        rescue.enqueueOrUpdate(restarted.prepareForForward(outgoing), 1_000_200L)
        assertEquals("DELIVERED", rescue.prioritizedQueue().single().status)
    }

    @Test
    fun `five node disconnected contacts deliver SOS and reverse ACK`() {
        val nodes = List(5) { MeshQueueEngine() }
        val t0 = 1_000_000L
        nodes[0].enqueueOrUpdate(samplePacket("FIVE-NODES", ttl = MeshQueueEngine.UNLIMITED_TTL), t0)
        for (i in 0..3) {
            val missing = nodes[i].computeMissingForPeer(nodes[i + 1].summaryVector())
            nodes[i + 1].enqueueOrUpdate(nodes[i].prepareForForward(missing.single()), t0 + (i + 1) * 100L)
        }
        assertEquals(4, nodes[4].prioritizedQueue().single().hopCount)
        val ack = samplePacket("ACK-5", type = "ACK_STATUS", status = "REACHED",
            ttl = MeshQueueEngine.UNLIMITED_TTL).copy(content = "FIVE-NODES|REACHED")
        nodes[4].enqueueOrUpdate(ack, t0 + 500L)
        var forwarded = ack
        for (i in 4 downTo 1) {
            forwarded = nodes[i].prepareForForward(forwarded)
            nodes[i - 1].enqueueOrUpdate(forwarded, t0 + (10 - i) * 100L)
        }
        assertEquals("REACHED", nodes[0].prioritizedQueue().first { it.id == "FIVE-NODES" }.status)
    }

    @Test
    fun `invalid routing values and future timestamps are rejected`() {
        val engine = MeshQueueEngine()
        for (p in listOf(samplePacket("NEG-TTL", ttl = -1), samplePacket("NEG-HOPS", hopCount = -1),
            samplePacket("BAD-STATE", status = "UNKNOWN"), samplePacket("FUTURE", createdAt = 2_000_000L))) {
            assertEquals(EnqueueResult.DroppedInvalidPacket, engine.enqueueOrUpdate(p, 1_000_000L))
        }
        assertEquals(0, engine.size())
    }

    @Test
    fun `codec rejects unknown and oversized frames and keeps summary versions`() {
        assertNull(MeshPacketCodec.decodeFrame("{\"frameType\":\"UNKNOWN\"}".toByteArray()))
        assertNull(MeshPacketCodec.decodeFrame(ByteArray(MeshPacketCodec.MAX_FRAME_BYTES + 1)))
        assertNull(MeshPacketCodec.decodeFrame("broken JSON".toByteArray()))
        val bytes = MeshPacketCodec.encodeSummaryVector("A", "Node A", 35, setOf("SOS"), mapOf("SOS" to 4))
        val decoded = MeshPacketCodec.decodeFrame(bytes) as DecodedMeshFrame.SummaryVector
        assertEquals(mapOf("SOS" to 4), decoded.statusRanks)
    }

    @Test
    fun `legacy summary with no status map remains decodable`() {
        val bytes = """{"frameType":"SUMMARY_VECTOR","nodeId":"OLD","packetIds":["SOS"]}""".toByteArray()
        val decoded = MeshPacketCodec.decodeFrame(bytes) as DecodedMeshFrame.SummaryVector
        assertEquals(setOf("SOS"), decoded.packetIds)
        assertTrue(decoded.statusRanks.isEmpty())
    }

    @Test
    fun `SOS location requires valid recent coordinates and declared accuracy`() {
        assertTrue(SosLocationPolicy.isUsable(21.0, 105.0, 30f, 1_000L, 2_000L))
        assertFalse(SosLocationPolicy.isUsable(Double.NaN, 105.0, 30f, 1_000L, 2_000L))
        assertFalse(SosLocationPolicy.isUsable(91.0, 105.0, 30f, 1_000L, 2_000L))
        assertFalse(SosLocationPolicy.isUsable(21.0, 105.0, 150f, 1_000L, 2_000L))
        assertFalse(SosLocationPolicy.isUsable(21.0, 105.0, 30f, 1_000L, 122_000L))
        assertFalse(SosLocationPolicy.isUsable(21.0, 105.0, 30f, 3_000L, 2_000L))
    }
}
