package vn.tlu.sosmesh.coremap.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.tlu.sosmesh.coremap.cloud.FirebaseMeshGateway
import vn.tlu.sosmesh.coremap.db.MeshLogEntity
import vn.tlu.sosmesh.coremap.db.MeshMessageEntity
import vn.tlu.sosmesh.coremap.db.SosMeshDatabase
import vn.tlu.sosmesh.coremap.mesh.DecodedMeshFrame
import vn.tlu.sosmesh.coremap.mesh.MeshPacketCodec
import vn.tlu.sosmesh.coremap.mesh.MeshQueueEngine
import vn.tlu.sosmesh.coremap.mesh.NearbyMeshTransport
import vn.tlu.sosmesh.coremap.power.BatteryScanController
import vn.tlu.sosmesh.coremap.power.ScanProfile

class MeshForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var database: SosMeshDatabase
    private val queueEngine = MeshQueueEngine()
    private lateinit var nearbyTransport: NearbyMeshTransport
    private lateinit var firebaseGateway: FirebaseMeshGateway
    private var currentProfile: ScanProfile =
        BatteryScanController.selectAdaptiveProfile(100, false, false)

    private val pauseDiscoveryRunnable = Runnable {
        if (::nearbyTransport.isInitialized && nearbyTransport.connectedPeerCount() == 0) {
            nearbyTransport.pauseDiscoveryWindow()
        }
    }

    private val scanLoopRunnable = object : Runnable {
        override fun run() {
            serviceScope.launch {
                performAdaptiveScanTick()
                val batteryPct = BatteryScanController.currentBatteryPercent(applicationContext)
                withContext(Dispatchers.Main) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIFICATION_ID, buildForegroundNotification(batteryPct))
                    handler.removeCallbacks(pauseDiscoveryRunnable)
                    handler.postDelayed(pauseDiscoveryRunnable, currentProfile.advertiseWindowMs)
                    scheduleNextTick(currentProfile.scanIntervalMs)
                }
            }
        }
    }

    private fun scheduleNextTick(delayMs: Long) {
        handler.removeCallbacks(scanLoopRunnable)
        handler.postDelayed(scanLoopRunnable, delayMs)
    }

    override fun onCreate() {
        super.onCreate()
        vn.tlu.sosmesh.coremap.alert.AlertSignatureVerifier.loadPersistedTrustStore(applicationContext)
        database = SosMeshDatabase.getInstance(applicationContext)
        nearbyTransport = NearbyMeshTransport(applicationContext, database, queueEngine, serviceScope)
        firebaseGateway = FirebaseMeshGateway(applicationContext, database, queueEngine)
        createNotificationChannel()
        serviceScope.launch {
            val profile = database.getOrCreateProfile(applicationContext)
            database.messageDao().getAllMessages().forEach { queueEngine.enqueueOrUpdate(it) }
            nearbyTransport.startRadioWindow(
                nodeId = profile.userId,
                displayName = profile.displayName,
                scanCycleMs = currentProfile.scanIntervalMs
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val batteryPct = BatteryScanController.currentBatteryPercent(applicationContext)
        startForeground(NOTIFICATION_ID, buildForegroundNotification(batteryPct))

        val rawPacketBytes = intent?.getByteArrayExtra(EXTRA_PACKET_JSON_BYTES)
        if (rawPacketBytes != null) {
            serviceScope.launch {
                val decoded = MeshPacketCodec.decodeFrame(rawPacketBytes)
                if (decoded is DecodedMeshFrame.Packet) {
                    nearbyTransport.broadcastPacketNow(decoded.message)
                    firebaseGateway.syncIfOnline(nearbyTransport, currentProfile.scanIntervalMs)
                }
            }
        }

        handler.removeCallbacks(scanLoopRunnable)
        handler.post(scanLoopRunnable)
        return START_STICKY
    }

    private suspend fun performAdaptiveScanTick() {
        val now = System.currentTimeMillis()
        val batteryPct = BatteryScanController.currentBatteryPercent(applicationContext)
        val profile = database.getOrCreateProfile(applicationContext)
        val allMessages = database.messageDao().getAllMessages()
        allMessages.forEach { queueEngine.enqueueOrUpdate(it, now) }

        val expiredCount = queueEngine.purgeExpired(now)
        database.messageDao().deleteExpiredMessages(now, 6 * 3600 * 1000L)

        val hasActiveSos = allMessages.any {
            it.type == "SOS" && (it.status == "QUEUED" || it.status == "BROADCASTING")
        }
        currentProfile = BatteryScanController.selectAdaptiveProfile(
            batteryPct = batteryPct,
            userBatterySaverEnabled = profile.batterySaverEnabled,
            hasActiveCriticalSos = hasActiveSos
        )

        nearbyTransport.startRadioWindow(
            nodeId = profile.userId,
            displayName = profile.displayName,
            scanCycleMs = currentProfile.scanIntervalMs
        )

        firebaseGateway.syncIfOnline(nearbyTransport, currentProfile.scanIntervalMs)

        if (expiredCount > 0) {
            database.logDao().insertLog(
                MeshLogEntity(
                    packetId = "TTL-CLEANUP",
                    packetType = "SYSTEM",
                    origin = profile.userId,
                    event = "PURGED_$expiredCount",
                    hopCount = 0,
                    latencyMs = 0L,
                    batteryPct = batteryPct,
                    scanCycleMs = currentProfile.scanIntervalMs,
                    timestamp = now
                )
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SOS Mesh Foreground Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Duy trì kết nối P2P Mesh & định vị khẩn cấp khi bị cô lập"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(batteryPct: Int): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val peerCount = if (::nearbyTransport.isInitialized) nearbyTransport.connectedPeerCount() else 0
        return builder
            .setContentTitle("SOS Mesh đang chạy nền (${currentProfile.label})")
            .setContentText("Pin: $batteryPct% · Hàng đợi: ${queueEngine.size()} gói · Nút P2P_CLUSTER: $peerCount")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(scanLoopRunnable)
        handler.removeCallbacks(pauseDiscoveryRunnable)
        if (::nearbyTransport.isInitialized) {
            nearbyTransport.stopAll()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "sos_mesh_foreground_channel"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_PACKET_JSON_BYTES = "extra_packet_json_bytes"

        fun startOrRefresh(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun broadcastPacket(context: Context, packet: MeshMessageEntity) {
            val intent = Intent(context, MeshForegroundService::class.java).apply {
                putExtra(EXTRA_PACKET_JSON_BYTES, MeshPacketCodec.encodePacket(packet))
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }
    }
}
