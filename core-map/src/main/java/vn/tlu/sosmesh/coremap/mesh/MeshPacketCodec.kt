package vn.tlu.sosmesh.coremap.mesh

import org.json.JSONArray
import org.json.JSONObject
import vn.tlu.sosmesh.coremap.db.MeshMessageEntity

object MeshPacketCodec {
    const val FRAME_SUMMARY_VECTOR = "SUMMARY_VECTOR"
    const val FRAME_PACKET = "PACKET"

    fun encodeSummaryVector(
        nodeId: String,
        deviceName: String,
        androidApi: Int,
        packetIds: Set<String>
    ): ByteArray {
        val json = JSONObject().apply {
            put("frameType", FRAME_SUMMARY_VECTOR)
            put("nodeId", nodeId)
            put("deviceName", deviceName)
            put("androidApi", androidApi)
            put("packetIds", JSONArray(packetIds.toList()))
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun encodePacket(packet: MeshMessageEntity): ByteArray {
        val json = JSONObject().apply {
            put("frameType", FRAME_PACKET)
            put("id", packet.id)
            put("type", packet.type)
            put("origin", packet.senderId)
            put("senderName", packet.senderName)
            put("latitude", packet.latitude)
            put("longitude", packet.longitude)
            put("peopleCount", packet.peopleCount)
            put("conditionSummary", packet.conditionSummary)
            put("urgencyLevel", packet.urgencyLevel)
            put("content", packet.content)
            if (packet.signature != null) {
                put("signature", packet.signature)
            }
            put("createdAt", packet.createdAt)
            put("updatedAt", packet.updatedAt)
            put("ttl", packet.ttl)
            put("hops", packet.hopCount)
            put("status", packet.status)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeFrame(bytes: ByteArray): DecodedMeshFrame? {
        return runCatching {
            val text = bytes.toString(Charsets.UTF_8)
            val json = JSONObject(text)
            when (json.optString("frameType", FRAME_PACKET)) {
                FRAME_SUMMARY_VECTOR -> {
                    val arr = json.optJSONArray("packetIds") ?: JSONArray()
                    val ids = LinkedHashSet<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        val id = arr.optString(i)
                        if (id.isNotBlank()) ids.add(id)
                    }
                    DecodedMeshFrame.SummaryVector(
                        nodeId = json.optString("nodeId", "UNKNOWN"),
                        deviceName = json.optString("deviceName", "Nearby Peer"),
                        androidApi = json.optInt("androidApi", 26),
                        packetIds = ids
                    )
                }

                else -> {
                    val id = json.getString("id")
                    val type = json.getString("type")
                    val origin = json.optString("origin", json.optString("senderId", "UNKNOWN"))
                    val createdAt = json.getLong("createdAt")
                    val ttl = json.getInt("ttl")
                    val hops = json.optInt("hops", json.optInt("hopCount", 0))
                    val entity = MeshMessageEntity(
                        id = id,
                        type = type,
                        senderId = origin,
                        senderName = json.optString("senderName", origin),
                        latitude = json.optDouble("latitude", 0.0),
                        longitude = json.optDouble("longitude", 0.0),
                        peopleCount = json.optInt("peopleCount", 1),
                        conditionSummary = json.optString("conditionSummary", ""),
                        urgencyLevel = json.optString("urgencyLevel", "HIGH"),
                        content = json.optString("content", ""),
                        signature = json.optString("signature", "").takeIf { it.isNotBlank() },
                        ttl = ttl,
                        hopCount = hops,
                        status = json.optString("status", "BROADCASTING"),
                        createdAt = createdAt,
                        updatedAt = json.optLong("updatedAt", createdAt)
                    )
                    DecodedMeshFrame.Packet(entity)
                }
            }
        }.getOrNull()
    }
}

sealed interface DecodedMeshFrame {
    data class SummaryVector(
        val nodeId: String,
        val deviceName: String,
        val androidApi: Int,
        val packetIds: Set<String>
    ) : DecodedMeshFrame

    data class Packet(val message: MeshMessageEntity) : DecodedMeshFrame
}
