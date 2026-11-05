package vn.tlu.sosmesh.coremap.mesh

import vn.tlu.sosmesh.coremap.alert.AlertSignatureVerifier
import vn.tlu.sosmesh.coremap.db.MeshMessageEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MeshQueueEngine(
    private val maxQueueSize: Int = 200,
    private val maxAgeMs: Long = 6 * 3600 * 1000L,
    private val maxSeenPackets: Int = maxQueueSize * 10
) {
    private val seenPacketIds = LinkedHashMap<String, Long>()
    private val queue = LinkedHashMap<String, MeshMessageEntity>()

    init {
        require(maxQueueSize > 0 && maxAgeMs > 0 && maxSeenPackets >= maxQueueSize)
    }

    @Synchronized
    fun enqueueOrUpdate(packet: MeshMessageEntity, nowMs: Long = System.currentTimeMillis()): EnqueueResult {
        if (nowMs - packet.createdAt >= maxAgeMs) {
            return EnqueueResult.DroppedStale
        }
        if (packet.ttl < 0 || packet.hopCount < 0 || packet.id.isBlank() ||
            packet.type !in setOf("SOS", "ALERT", "ACK_STATUS", "CHAT") ||
            packet.status !in STATUSES || packet.createdAt > nowMs + 5 * 60_000L
        ) return EnqueueResult.DroppedInvalidPacket
        if (packet.type == "ALERT" && !AlertSignatureVerifier.verifyAlert(packet)) {
            return EnqueueResult.DroppedInvalidSignature
        }

        if (packet.type == "ACK_STATUS" && parseAck(packet) == null) {
            return EnqueueResult.DroppedInvalidPacket
        }

        if (seenPacketIds.containsKey(packet.id)) {
            val existing = queue[packet.id]
            if (existing != null && statusRank(packet.status) > statusRank(existing.status)) {
                queue[packet.id] = existing.copy(status = packet.status, updatedAt = nowMs)
                return EnqueueResult.UpdatedStatus(queue[packet.id]!!)
            }
            return EnqueueResult.DuplicateDropped
        }

        seenPacketIds[packet.id] = packet.createdAt
        // An ACK may arrive before its SOS during store-carry-forward.
        val pendingStatus = if (packet.type == "SOS") queue.values
            .filter { it.type == "ACK_STATUS" }
            .mapNotNull(::parseAck)
            .filter { it.first == packet.id }
            .map { it.second }
            .maxByOrNull(::statusRank) else null
        val accepted = if (pendingStatus != null && statusRank(pendingStatus) > statusRank(packet.status)) {
            packet.copy(status = pendingStatus, updatedAt = nowMs)
        } else packet
        queue[packet.id] = accepted
        parseAck(packet)?.let { (targetId, newStatus) ->
            val target = queue[targetId]
            if (target?.type == "SOS" && statusRank(newStatus) > statusRank(target.status)) {
                queue[targetId] = target.copy(status = newStatus, updatedAt = nowMs)
            }
        }
        trimIfNeeded()
        while (seenPacketIds.size > maxSeenPackets) {
            val removable = seenPacketIds.keys.firstOrNull { !queue.containsKey(it) } ?: break
            seenPacketIds.remove(removable)
        }
        return EnqueueResult.Enqueued(accepted)
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
    fun summaryVector(): Set<String> = LinkedHashSet(queue.keys)

    @Synchronized
    fun summaryStatusRanks(): Map<String, Int> = queue.mapValues { statusRank(it.value.status) }

    @Synchronized
    fun computeMissingForPeer(
        peerSummaryVector: Set<String>,
        peerStatusRanks: Map<String, Int> = emptyMap()
    ): List<MeshMessageEntity> {
        return prioritizedQueue().filter {
            it.ttl > 0 && (!peerSummaryVector.contains(it.id) ||
                (peerStatusRanks[it.id]?.let { rank -> rank < statusRank(it.status) } == true))
        }
    }

    @Synchronized
    fun prepareForForward(packet: MeshMessageEntity): MeshMessageEntity {
        require(packet.ttl > 0 && packet.hopCount < Int.MAX_VALUE)
        return packet.copy(
            ttl = if (packet.ttl == UNLIMITED_TTL) UNLIMITED_TTL else packet.ttl - 1,
            hopCount = packet.hopCount + 1
        )
    }

    @Synchronized
    fun purgeExpired(nowMs: Long = System.currentTimeMillis()): Int {
        val iter = queue.entries.iterator()
        var removed = 0
        while (iter.hasNext()) {
            val entry = iter.next().value
            if (nowMs - entry.createdAt >= maxAgeMs) {
                iter.remove()
                removed++
            }
        }
        seenPacketIds.entries.removeAll { nowMs - it.value >= maxAgeMs }
        return removed
    }

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun seenCount(): Int = seenPacketIds.size

    private fun trimIfNeeded() {
        if (queue.size <= maxQueueSize) return
        val sorted = prioritizedQueue()
        val toRemove = sorted.drop(maxQueueSize)
        for (item in toRemove) {
            queue.remove(item.id)
        }
    }

    companion object {
        const val UNLIMITED_TTL: Int = Int.MAX_VALUE
        val STATUSES = setOf("QUEUED", "BROADCASTING", "DELIVERED", "APPROACHING", "REACHED")

        fun parseAck(packet: MeshMessageEntity): Pair<String, String>? {
            if (packet.type != "ACK_STATUS") return null
            val parts = packet.content.split('|')
            if (parts.size != 2 || parts[0].isBlank() ||
                parts[1] !in setOf("DELIVERED", "APPROACHING", "REACHED") ||
                packet.status != parts[1]
            ) return null
            return parts[0] to parts[1]
        }

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
    data object DroppedInvalidPacket : EnqueueResult
}
