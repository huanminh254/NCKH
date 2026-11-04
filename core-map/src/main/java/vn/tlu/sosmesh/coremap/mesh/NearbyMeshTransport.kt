package vn.tlu.sosmesh.coremap.mesh

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import vn.tlu.sosmesh.coremap.alert.EmergencyAlertNotifier
import vn.tlu.sosmesh.coremap.db.EncounteredPeerEntity
import vn.tlu.sosmesh.coremap.db.MeshLogEntity
import vn.tlu.sosmesh.coremap.db.MeshMessageEntity
import vn.tlu.sosmesh.coremap.db.SosMeshDatabase
import vn.tlu.sosmesh.coremap.power.BatteryScanController
import java.util.concurrent.ConcurrentHashMap

class NearbyMeshTransport(
    private val context: Context,
    private val database: SosMeshDatabase,
    private val queueEngine: MeshQueueEngine,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(appContext)
    }

    private val discoveredNames = ConcurrentHashMap<String, String>()
    private val connectedEndpoints = ConcurrentHashMap<String, String>()
    private val exchangedCounts = ConcurrentHashMap<String, Int>()

    @Volatile
    private var localNodeId: String = "NODE-LOCAL"

    @Volatile
    private var localDisplayName: String = Build.MODEL ?: "Android"

    @Volatile
    private var currentScanCycleMs: Long = 15_000L

    @Volatile
    var isAdvertising: Boolean = false
        private set

    @Volatile
    var isDiscovering: Boolean = false
        private set

    fun connectedPeerCount(): Int = connectedEndpoints.size

    fun startRadioWindow(nodeId: String, displayName: String, scanCycleMs: Long) {
        localNodeId = nodeId
        localDisplayName = displayName
        currentScanCycleMs = scanCycleMs
        startAdvertisingIfNeeded()
        startDiscoveryIfNeeded()
    }

    fun pauseDiscoveryWindow() {
        if (isDiscovering) {
            runCatching {
                connectionsClient.stopDiscovery()
            }
            isDiscovering = false
            Log.d(TAG, "Adaptive scan: paused Nearby discovery window")
        }
    }

    fun stopAll() {
        runCatching {
            connectionsClient.stopDiscovery()
            connectionsClient.stopAdvertising()
            connectionsClient.stopAllEndpoints()
        }
        isDiscovering = false
        isAdvertising = false
        connectedEndpoints.clear()
    }

    private fun startAdvertisingIfNeeded() {
        if (isAdvertising) return
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()
        val endpointLabel = "$localNodeId|$localDisplayName"
        connectionsClient
            .startAdvertising(endpointLabel, SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                isAdvertising = true
                Log.i(TAG, "Nearby P2P_CLUSTER advertising started as $endpointLabel")
            }
            .addOnFailureListener { e ->
                if (e.message?.contains("8001") == true) {
                    isAdvertising = true
                } else {
                    isAdvertising = false
                    Log.w(TAG, "Nearby startAdvertising failed: ${e.message}")
                }
            }
    }

    private fun startDiscoveryIfNeeded() {
        if (isDiscovering) return
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()
        connectionsClient
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                isDiscovering = true
                Log.i(TAG, "Nearby P2P_CLUSTER discovery started")
            }
            .addOnFailureListener { e ->
                if (e.message?.contains("8002") == true) {
                    isDiscovering = true
                } else {
                    isDiscovering = false
                    Log.w(TAG, "Nearby startDiscovery failed: ${e.message}")
                }
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            discoveredNames[endpointId] = info.endpointName
            if (!connectedEndpoints.containsKey(endpointId)) {
                val localEndpointName = "$localNodeId|$localDisplayName"
                connectionsClient
                    .requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
                    .addOnFailureListener { e ->
                        Log.d(TAG, "requestConnection to $endpointId skipped/failed: ${e.message}")
                    }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            discoveredNames.remove(endpointId)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            discoveredNames[endpointId] = connectionInfo.endpointName
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                val name = discoveredNames[endpointId] ?: endpointId
                connectedEndpoints[endpointId] = name
                scope.launch {
                    recordPeerSeen(
                        endpointId = endpointId,
                        deviceName = name,
                        androidApi = Build.VERSION.SDK_INT,
                        deltaPackets = 0
                    )
                    sendSummaryVectorToPeer(endpointId)
                }
            } else {
                connectedEndpoints.remove(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val decoded = MeshPacketCodec.decodeFrame(bytes) ?: return
            scope.launch {
                when (decoded) {
                    is DecodedMeshFrame.SummaryVector -> handleSummaryVector(endpointId, decoded)
                    is DecodedMeshFrame.Packet -> handleIncomingPacket(endpointId, decoded.message)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private suspend fun sendSummaryVectorToPeer(endpointId: String) {
        val profile = database.getOrCreateProfile(appContext)
        val summaryBytes = MeshPacketCodec.encodeSummaryVector(
            nodeId = profile.userId,
            deviceName = profile.displayName,
            androidApi = Build.VERSION.SDK_INT,
            packetIds = queueEngine.summaryVector()
        )
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(summaryBytes))
    }

    private suspend fun handleSummaryVector(
        endpointId: String,
        summary: DecodedMeshFrame.SummaryVector
    ) {
        recordPeerSeen(
            endpointId = endpointId,
            deviceName = summary.deviceName,
            androidApi = summary.androidApi,
            deltaPackets = 0
        )
        val missing = queueEngine.computeMissingForPeer(summary.packetIds)
        val now = System.currentTimeMillis()
        val batteryPct = BatteryScanController.currentBatteryPercent(appContext)
        for (packet in missing) {
            val outgoing = if (packet.senderId == localNodeId) packet else queueEngine.prepareForForward(packet)
            val payload = Payload.fromBytes(MeshPacketCodec.encodePacket(outgoing))
            connectionsClient.sendPayload(endpointId, payload)
            recordPeerSeen(endpointId, summary.deviceName, summary.androidApi, deltaPackets = 1)
            if (packet.status == "QUEUED") {
                database.messageDao().updateStatus(packet.id, "BROADCASTING", now)
            }
            val eventType = when {
                packet.type == "ACK_STATUS" -> "ACK"
                packet.senderId == localNodeId -> "SENT"
                else -> "FORWARDED"
            }
            database.logDao().insertLog(
                MeshLogEntity(
                    packetId = outgoing.id,
                    packetType = outgoing.type,
                    origin = outgoing.senderId,
                    event = eventType,
                    hopCount = outgoing.hopCount,
                    latencyMs = (now - packet.createdAt).coerceAtLeast(0L),
                    batteryPct = batteryPct,
                    scanCycleMs = currentScanCycleMs,
                    timestamp = now
                )
            )
        }
        if (missing.isNotEmpty()) {
            database.exportLogsToCsv(appContext)
        }
    }

    suspend fun handleIncomingPacket(fromEndpointId: String, incoming: MeshMessageEntity) {
        val receivedAt = System.currentTimeMillis()
        val realLatencyMs = (receivedAt - incoming.createdAt).coerceAtLeast(0L)
        val batteryPct = BatteryScanController.currentBatteryPercent(appContext)
        val peerLabel = connectedEndpoints[fromEndpointId] ?: discoveredNames[fromEndpointId] ?: fromEndpointId
        recordPeerSeen(fromEndpointId, peerLabel, Build.VERSION.SDK_INT, deltaPackets = 1)

        val result = queueEngine.enqueueOrUpdate(incoming, receivedAt)
        val acceptedPacket = when (result) {
            is EnqueueResult.Enqueued -> result.packet
            is EnqueueResult.UpdatedStatus -> result.packet
            else -> null
        } ?: return

        database.messageDao().upsertMessage(acceptedPacket)

        if (incoming.type == "ACK_STATUS") {
            val targetId = incoming.content.substringBefore("|").trim()
            val newStatus = incoming.content.substringAfter("|", incoming.status).trim()
            if (targetId.isNotBlank()) {
                database.messageDao().updateStatus(targetId, newStatus, receivedAt)
            }
            database.logDao().insertLog(
                MeshLogEntity(
                    packetId = targetId.ifBlank { incoming.id },
                    packetType = "ACK_STATUS",
                    origin = incoming.senderId,
                    event = "ACK",
                    hopCount = incoming.hopCount,
                    latencyMs = realLatencyMs,
                    batteryPct = batteryPct,
                    scanCycleMs = currentScanCycleMs,
                    timestamp = receivedAt
                )
            )
        } else {
            database.logDao().insertLog(
                MeshLogEntity(
                    packetId = incoming.id,
                    packetType = incoming.type,
                    origin = incoming.senderId,
                    event = "RECEIVED",
                    hopCount = incoming.hopCount,
                    latencyMs = realLatencyMs,
                    batteryPct = batteryPct,
                    scanCycleMs = currentScanCycleMs,
                    timestamp = receivedAt
                )
            )

            if (incoming.type == "ALERT") {
                EmergencyAlertNotifier.notifyCriticalAlert(appContext, incoming)
            }

            val profile = database.getOrCreateProfile(appContext)
            if (incoming.type == "SOS" &&
                incoming.senderId != profile.userId &&
                profile.activeMode == "RESCUE" &&
                MeshQueueEngine.statusRank(incoming.status) < MeshQueueEngine.statusRank("DELIVERED")
            ) {
                database.messageDao().updateStatus(incoming.id, "DELIVERED", receivedAt)
                val deliveredAck = MeshMessageEntity(
                    id = "ACK-${incoming.id}-DELIVERED-${profile.userId}",
                    type = "ACK_STATUS",
                    senderId = profile.userId,
                    senderName = profile.displayName,
                    latitude = incoming.latitude,
                    longitude = incoming.longitude,
                    peopleCount = incoming.peopleCount,
                    conditionSummary = "Máy cứu hộ đã nhận được SOS",
                    urgencyLevel = incoming.urgencyLevel,
                    content = "${incoming.id}|DELIVERED",
                    signature = null,
                    ttl = MeshQueueEngine.UNLIMITED_TTL,
                    hopCount = 0,
                    status = "DELIVERED",
                    createdAt = receivedAt,
                    updatedAt = receivedAt
                )
                broadcastPacketNow(deliveredAck)
            }
        }

        val forwardPacket = queueEngine.prepareForForward(acceptedPacket)
        val otherEndpoints = connectedEndpoints.keys.filter { it != fromEndpointId }
        if (otherEndpoints.isNotEmpty()) {
            val payloadBytes = MeshPacketCodec.encodePacket(forwardPacket)
            for (targetEndpoint in otherEndpoints) {
                connectionsClient.sendPayload(targetEndpoint, Payload.fromBytes(payloadBytes))
            }
            database.logDao().insertLog(
                MeshLogEntity(
                    packetId = forwardPacket.id,
                    packetType = forwardPacket.type,
                    origin = forwardPacket.senderId,
                    event = "FORWARDED",
                    hopCount = forwardPacket.hopCount,
                    latencyMs = (System.currentTimeMillis() - forwardPacket.createdAt).coerceAtLeast(0L),
                    batteryPct = batteryPct,
                    scanCycleMs = currentScanCycleMs,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        database.exportLogsToCsv(appContext)
    }

    suspend fun broadcastPacketNow(packet: MeshMessageEntity) {
        val now = System.currentTimeMillis()
        val batteryPct = BatteryScanController.currentBatteryPercent(appContext)
        queueEngine.enqueueOrUpdate(packet, now)
        database.messageDao().upsertMessage(packet)

        if (packet.type == "ACK_STATUS") {
            val targetId = packet.content.substringBefore("|").trim()
            val newStatus = packet.content.substringAfter("|", packet.status).trim()
            if (targetId.isNotBlank()) {
                database.messageDao().updateStatus(targetId, newStatus, now)
            }
        }

        val eventName = if (packet.type == "ACK_STATUS") "ACK" else "SENT"
        database.logDao().insertLog(
            MeshLogEntity(
                packetId = packet.id,
                packetType = packet.type,
                origin = packet.senderId,
                event = eventName,
                hopCount = packet.hopCount,
                latencyMs = (now - packet.createdAt).coerceAtLeast(0L),
                batteryPct = batteryPct,
                scanCycleMs = currentScanCycleMs,
                timestamp = now
            )
        )

        val endpoints = connectedEndpoints.keys.toList()
        if (endpoints.isNotEmpty()) {
            val payloadBytes = MeshPacketCodec.encodePacket(packet)
            for (ep in endpoints) {
                connectionsClient.sendPayload(ep, Payload.fromBytes(payloadBytes))
            }
            if (packet.status == "QUEUED") {
                database.messageDao().updateStatus(packet.id, "BROADCASTING", now)
            }
        }
        database.exportLogsToCsv(appContext)
    }

    private suspend fun recordPeerSeen(
        endpointId: String,
        deviceName: String,
        androidApi: Int,
        deltaPackets: Int
    ) {
        val totalExchanged = exchangedCounts.compute(endpointId) { _, prev ->
            (prev ?: 0) + deltaPackets
        } ?: deltaPackets
        val cleanName = deviceName.substringAfter("|", deviceName)
        database.peerDao().upsertPeer(
            EncounteredPeerEntity(
                endpointId = endpointId,
                deviceName = cleanName,
                androidApi = androidApi,
                supportsBle = true,
                supportsWifiDirect = true,
                lastRssi = -55,
                exchangedPackets = totalExchanged,
                lastSeenAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        const val SERVICE_ID = "vn.tlu.sosmesh.p2p.CLUSTER_V1"
        private const val TAG = "NearbyMeshTransport"
    }
}
