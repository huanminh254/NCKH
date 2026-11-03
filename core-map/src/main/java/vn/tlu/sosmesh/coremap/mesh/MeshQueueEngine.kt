package vn.tlu.sosmesh.coremap.mesh

import vn.tlu.sosmesh.coremap.alert.AlertSignatureVerifier
import vn.tlu.sosmesh.coremap.db.MeshMessageEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MeshQueueEngine(
    private val maxQueueSize: Int = 200,
    private val maxAgeMs: Long = 6 * 3600 * 1000L
) {
    private val seenPacketIds = LinkedHashSet<String>()
    private val queue = LinkedHashMap<String, MeshMessageEntity>()

    @Synchronized
    fun enqueueOrUpdate(packet: MeshMessageEntity, nowMs: Long = System.currentTimeMillis()): EnqueueResult {
        if (packet.ttl <= 0) {
            return EnqueueResult.DroppedTtlExpired
        }
        if (nowMs - packet.createdAt > maxAgeMs) {
            return EnqueueResult.DroppedStale
        }
        if (packet.type == "ALERT" && !AlertSignatureVerifier.verifyAlert(packet)) {
            return EnqueueResult.DroppedInvalidSignature
        }

        if (packet.type == "ACK_STATUS") {
            val targetId = packet.content.substringBefore("|").trim()
            val newStatus = packet.content.substringAfter("|", packet.status).trim()
            val existingTarget = queue[targetId]
            if (existingTarget != null && statusRank(newStatus) > statusRank(existingTarget.status)) {
                queue[targetId] = existingTarget.copy(status = newStatus, updatedAt = nowMs)
            }
        }

        if (seenPacketIds.contains(packet.id)) {
            val existing = queue[packet.id]
            if (existing != null && statusRank(packet.status) > statusRank(existing.status)) {
                queue[packet.id] = existing.copy(status = packet.status, updatedAt = nowMs)
                return EnqueueResult.UpdatedStatus(queue[packet.id]!!)
            }
            return EnqueueResult.DuplicateDropped
        }

        seenPacketIds.add(packet.id)
        queue[packet.id] = packet
        trimIfNeeded()
        return EnqueueResult.Enqueued(packet)
    }

    @Synchronized
    fun prioritizedQueue(): List<MeshMessageEntity> {
        return queue.values.sortedWith(
            compareBy<MeshMessageEntity>(
                { typePriority(it.type) },
                { urgencyPriority(it.urgencyLevel) },
                { it.createdAt }
            )
        )
    }

    @Synchronized
    fun summaryVector(): Set<String> = LinkedHashSet(seenPacketIds)

    @Synchronized
    fun computeMissingForPeer(peerSummaryVector: Set<String>): List<MeshMessageEntity> {
        return prioritizedQueue().filter { it.ttl > 0 && !peerSummaryVector.contains(it.id) }
    }

    @Synchronized
    fun prepareForForward(packet: MeshMessageEntity): MeshMessageEntity? {
        val nextTtl = packet.ttl - 1
        if (nextTtl <= 0) return null
        return packet.copy(ttl = nextTtl, hopCount = packet.hopCount + 1)
    }

    @Synchronized
    fun purgeExpired(nowMs: Long = System.currentTimeMillis()): Int {
        val iter = queue.entries.iterator()
        var removed = 0
        while (iter.hasNext()) {
            val entry = iter.next().value
            if (entry.ttl <= 0 || nowMs - entry.createdAt > maxAgeMs) {
                iter.remove()
                removed++
            }
        }
        return removed
    }

    @Synchronized
    fun size(): Int = queue.size

    private fun trimIfNeeded() {
        if (queue.size <= maxQueueSize) return
        val sorted = prioritizedQueue()
        val toRemove = sorted.drop(maxQueueSize)
        for (item in toRemove) {
            queue.remove(item.id)
        }
    }

    companion object {
        fun verifyAlertSignature(packet: MeshMessageEntity): Boolean {
            return AlertSignatureVerifier.verifyAlert(packet)
        }

        fun typePriority(type: String): Int = when (type) {
            "SOS" -> 0
            "ALERT" -> 1
            "ACK_STATUS" -> 2
            "CHAT" -> 3
            else -> 4
        }

        fun urgencyPriority(urgency: String): Int = when (urgency) {
            "CRITICAL" -> 0
            "HIGH" -> 1
            "MEDIUM" -> 2
            else -> 3
        }

        fun statusRank(status: String): Int = when (status) {
            "QUEUED" -> 0
            "BROADCASTING" -> 1
            "DELIVERED" -> 2
            "APPROACHING" -> 3
            "REACHED" -> 4
            else -> 0
        }

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return r * c
        }
    }
}

sealed interface EnqueueResult {
    data class Enqueued(val packet: MeshMessageEntity) : EnqueueResult
    data class UpdatedStatus(val packet: MeshMessageEntity) : EnqueueResult
    data object DuplicateDropped : EnqueueResult
    data object DroppedTtlExpired : EnqueueResult
    data object DroppedStale : EnqueueResult
    data object DroppedInvalidSignature : EnqueueResult
}
