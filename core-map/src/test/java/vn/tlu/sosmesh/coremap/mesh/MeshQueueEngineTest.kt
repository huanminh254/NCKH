package vn.tlu.sosmesh.coremap.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.tlu.sosmesh.coremap.db.MeshMessageEntity

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
    fun `priority queue orders CRITICAL SOS before HIGH SOS before ALERT before CHAT`() {
        val engine = MeshQueueEngine()
        val chat = samplePacket(id = "CHAT-1", type = "CHAT", urgency = "MEDIUM", createdAt = 100L)
        val alert = samplePacket(
            id = "ALERT-1",
            type = "ALERT",
            urgency = "CRITICAL",
            signature = "SIG-TLU-OFFICIAL-123",
            createdAt = 200L
        )
        val highSos = samplePacket(id = "SOS-HIGH", type = "SOS", urgency = "HIGH", createdAt = 300L)
        val critSos = samplePacket(id = "SOS-CRIT", type = "SOS", urgency = "CRITICAL", createdAt = 400L)

        engine.enqueueOrUpdate(chat, 1000L)
        engine.enqueueOrUpdate(alert, 1000L)
        engine.enqueueOrUpdate(highSos, 1000L)
        engine.enqueueOrUpdate(critSos, 1000L)

        val orderedIds = engine.prioritizedQueue().map { it.id }
        assertEquals(listOf("SOS-CRIT", "SOS-HIGH", "ALERT-1", "CHAT-1"), orderedIds)
    }

    @Test
    fun `invalid ALERT signature is rejected`() {
        val engine = MeshQueueEngine()
        val unsignedAlert = samplePacket(id = "ALERT-FAKE", type = "ALERT", signature = "FAKE-SIG")
        assertEquals(
            EnqueueResult.DroppedInvalidSignature,
            engine.enqueueOrUpdate(unsignedAlert, 1_000_000L)
        )
        assertEquals(0, engine.size())
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
}
