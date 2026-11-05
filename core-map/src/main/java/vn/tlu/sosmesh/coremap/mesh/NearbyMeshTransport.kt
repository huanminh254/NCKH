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
    private data class PendingTransfer(
        val endpointId: String,
        val packet: MeshMessageEntity,
        val event: String
    )
    private val pendingTransfers = ConcurrentHashMap<Long, PendingTransfer>()
    private val inFlightPackets = ConcurrentHashMap.newKeySet<String>()

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
        pendingTransfers.clear()
        inFlightPackets.clear()
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
                        androidApi = 0,
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
            pendingTransfers.entries.filter { it.value.endpointId == endpointId }.forEach {
                pendingTransfers.remove(it.key)
                inFlightPackets.remove(transferKey(it.value))
            }
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

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) return
            val pending = pendingTransfers.remove(update.payloadId) ?: return
            inFlightPackets.remove(transferKey(pending))
            scope.launch {
                val success = update.status == PayloadTransferUpdate.Status.SUCCESS
                recordTransfer(pending, success)
            }
        }
    }

    private fun transferKey(transfer: PendingTransfer): String =
        "${transfer.endpointId}|${transfer.packet.id}|${transfer.packet.status}"

    private fun sendPacketToPeer(endpointId: String, packet: MeshMessageEntity, event: String) {
        val pending = PendingTransfer(endpointId, packet, event)
        if (!inFlightPackets.add(transferKey(pending))) return
        val payload = Payload.fromBytes(MeshPacketCodec.encodePacket(packet))
        pendingTransfers[payload.id] = pending
        connectionsClient.sendPayload(endpointId, payload).addOnFailureListener {
            if (pendingTransfers.remove(payload.id) != null) {
                inFlightPackets.remove(transferKey(pending))
                scope.launch { recordTransfer(pending, false) }
            }
        }
    }

    private suspend fun recordTransfer(transfer: PendingTransfer, success: Boolean) {
        val now = System.currentTimeMillis()
        val packet = transfer.packet
        if (success) {
            recordPeerSeen(transfer.endpointId,
                connectedEndpoints[transfer.endpointId] ?: transfer.endpointId, 0, 1)
            if (packet.status == "QUEUED") {
                database.messageDao().updateStatus(packet.id, "BROADCASTING", now)
                queueEngine.enqueueOrUpdate(packet.copy(status = "BROADCASTING"), now)
            }
        }
        database.logDao().insertLog(MeshLogEntity(
            packetId = packet.id, packetType = packet.type, origin = packet.senderId,
            event = if (success) transfer.event else "SEND_FAILED", hopCount = packet.hopCount,
            latencyMs = (now - packet.createdAt).coerceAtLeast(0L),
            batteryPct = BatteryScanController.currentBatteryPercent(appContext),
            scanCycleMs = currentScanCycleMs, timestamp = now
        ))
    }

    suspend fun exchangeSummaries() {
        for (endpointId in connectedEndpoints.keys.toList()) sendSummaryVectorToPeer(endpointId)
    }

    private suspend fun sendSummaryVectorToPeer(endpointId: String) {
        val profile = database.getOrCreateProfile(appContext)
        val summaryBytes = MeshPacketCodec.encodeSummaryVector(
            nodeId = profile.userId,
            deviceName = profile.displayName,
            androidApi = Build.VERSION.SDK_INT,
            packetIds = queueEngine.summaryVector(),
            statusRanks = queueEngine.summaryStatusRanks()
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
        val missing = queueEngine.computeMissingForPeer(summary.packetIds, summary.statusRanks)
        for (packet in missing) {
            val outgoing = queueEngine.prepareForForward(packet)
            val eventType = when {
                packet.type == "ACK_STATUS" -> "ACK"
                packet.senderId == localNodeId -> "SENT"
                else -> "FORWARDED"
            }
            sendPacketToPeer(endpointId, outgoing, eventType)
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
        recordPeerSeen(fromEndpointId, peerLabel, 0, deltaPackets = 1)

        val result = queueEngine.enqueueOrUpdate(incoming, receivedAt)
        val acceptedPacket = when (result) {
            is EnqueueResult.Enqueued -> result.packet
            is EnqueueResult.UpdatedStatus -> result.packet
            else -> null
        } ?: return

        database.messageDao().mergeMessage(acceptedPacket)

        if (incoming.type == "ACK_STATUS") {
            val (targetId, newStatus) = MeshQueueEngine.parseAck(incoming) ?: return
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
                queueEngine.enqueueOrUpdate(acceptedPacket.copy(status = "DELIVERED"), receivedAt)
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

        if (acceptedPacket.ttl == 0) return
        val forwardPacket = queueEngine.prepareForForward(acceptedPacket)
        val otherEndpoints = connectedEndpoints.keys.filter { it != fromEndpointId }
        if (otherEndpoints.isNotEmpty()) {
            for (targetEndpoint in otherEndpoints) {
                sendPacketToPeer(targetEndpoint, forwardPacket, "FORWARDED")
            }
        }

        database.exportLogsToCsv(appContext)
    }

    suspend fun broadcastPacketNow(packet: MeshMessageEntity) {
        val now = System.currentTimeMillis()
        val batteryPct = BatteryScanController.currentBatteryPercent(appContext)
        val result = queueEngine.enqueueOrUpdate(packet, now)
        val accepted = when (result) {
            is EnqueueResult.Enqueued -> result.packet
            is EnqueueResult.UpdatedStatus -> result.packet
            EnqueueResult.DuplicateDropped -> database.messageDao().getMessageById(packet.id)
            else -> null
        } ?: return
        database.messageDao().mergeMessage(accepted)

        if (packet.type == "ACK_STATUS") {
            val (targetId, newStatus) = MeshQueueEngine.parseAck(accepted) ?: return
            if (targetId.isNotBlank()) {
                database.messageDao().updateStatus(targetId, newStatus, now)
            }
        }

        val eventName = "QUEUED"
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
        if (endpoints.isNotEmpty() && accepted.ttl > 0) {
            val outgoing = queueEngine.prepareForForward(accepted)
            for (ep in endpoints) {
                sendPacketToPeer(ep, outgoing, if (packet.type == "ACK_STATUS") "ACK" else "SENT")
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
        val previousPeer = database.peerDao().getPeerById(endpointId)
        database.peerDao().upsertPeer(
            EncounteredPeerEntity(
                endpointId = endpointId,
                deviceName = cleanName,
                androidApi = if (androidApi > 0) androidApi else previousPeer?.androidApi ?: 0,
                supportsBle = previousPeer?.supportsBle ?: false,
                supportsWifiDirect = previousPeer?.supportsWifiDirect ?: false,
                lastRssi = 0, // Nearby does not expose RSSI; zero means not measured.
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
