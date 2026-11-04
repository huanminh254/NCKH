package vn.tlu.sosmesh.coremap.cloud

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.tlu.sosmesh.coremap.OfflineMbtilesHelper
import vn.tlu.sosmesh.coremap.alert.AlertSignatureVerifier
import vn.tlu.sosmesh.coremap.alert.EmergencyAlertNotifier
import vn.tlu.sosmesh.coremap.db.MeshLogEntity
import vn.tlu.sosmesh.coremap.db.MeshMessageEntity
import vn.tlu.sosmesh.coremap.db.SosMeshDatabase
import vn.tlu.sosmesh.coremap.mesh.EnqueueResult
import vn.tlu.sosmesh.coremap.mesh.MeshQueueEngine
import vn.tlu.sosmesh.coremap.mesh.NearbyMeshTransport
import vn.tlu.sosmesh.coremap.power.BatteryScanController
import java.util.concurrent.TimeUnit

class FirebaseMeshGateway(
    private val context: Context,
    private val database: SosMeshDatabase,
    private val queueEngine: MeshQueueEngine
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_GATEWAY, Context.MODE_PRIVATE)
    private val syncedIds = LinkedHashSet<String>(
        prefs.getStringSet(KEY_SYNCED_IDS, emptySet()).orEmpty()
    )

    fun isFirebaseConfigured(): Boolean {
        return runCatching {
            if (FirebaseApp.getApps(appContext).isNotEmpty()) {
                true
            } else {
                FirebaseApp.initializeApp(appContext) != null
            }
        }.getOrDefault(false)
    }

    suspend fun syncIfOnline(transport: NearbyMeshTransport, scanCycleMs: Long): CloudSyncSummary =
        withContext(Dispatchers.IO) {
            if (!OfflineMbtilesHelper.isNetworkAvailable(appContext)) {
                return@withContext CloudSyncSummary(online = false, configured = isFirebaseConfigured(), pushedCount = 0, pulledAlerts = 0)
            }
            if (!isFirebaseConfigured()) {
                return@withContext CloudSyncSummary(online = true, configured = false, pushedCount = 0, pulledAlerts = 0)
            }

            var pushed = 0
            var pulled = 0
            runCatching {
                val firestore = FirebaseFirestore.getInstance()
                val candidates = filterUnsyncedForUpload(queueEngine.prioritizedQueue(), syncedIds)
                for (msg in candidates) {
                    val docData = mapOf(
                        "id" to msg.id,
                        "type" to msg.type,
                        "origin" to msg.senderId,
                        "senderName" to msg.senderName,
                        "latitude" to msg.latitude,
                        "longitude" to msg.longitude,
                        "peopleCount" to msg.peopleCount,
                        "conditionSummary" to msg.conditionSummary,
                        "urgencyLevel" to msg.urgencyLevel,
                        "content" to msg.content,
                        "signature" to msg.signature,
                        "ttl" to msg.ttl,
                        "hops" to msg.hopCount,
                        "status" to msg.status,
                        "createdAt" to msg.createdAt,
                        "updatedAt" to msg.updatedAt
                    )
                    Tasks.await(
                        firestore.collection(COLLECTION_PACKETS).document(msg.id).set(docData),
                        8,
                        TimeUnit.SECONDS
                    )
                    markSynced(msg.id)
                    pushed++
                }

                val snapshot = Tasks.await(
                    firestore.collection(COLLECTION_ALERTS).get(),
                    8,
                    TimeUnit.SECONDS
                )
                val now = System.currentTimeMillis()
                val batteryPct = BatteryScanController.currentBatteryPercent(appContext)
                for (doc in snapshot.documents) {
                    val id = doc.getString("id") ?: doc.id
                    val alert = MeshMessageEntity(
                        id = id,
                        type = "ALERT",
                        senderId = doc.getString("origin") ?: doc.getString("senderId") ?: "OFFICIAL-CLOUD",
                        senderName = doc.getString("senderName") ?: "Ban Chỉ huy PCTT & TKCN",
                        latitude = doc.getDouble("latitude") ?: 21.0285,
                        longitude = doc.getDouble("longitude") ?: 105.8542,
                        peopleCount = (doc.getLong("peopleCount") ?: 0L).toInt(),
                        conditionSummary = doc.getString("conditionSummary") ?: "Cảnh báo thiên tai khẩn cấp",
                        urgencyLevel = doc.getString("urgencyLevel") ?: "CRITICAL",
                        content = doc.getString("content") ?: "",
                        signature = doc.getString("signature"),
                        ttl = (doc.getLong("ttl") ?: MeshQueueEngine.UNLIMITED_TTL.toLong()).toInt(),
                        hopCount = (doc.getLong("hops") ?: 0L).toInt(),
                        status = "DELIVERED",
                        createdAt = doc.getLong("createdAt") ?: now,
                        updatedAt = doc.getLong("updatedAt") ?: now
                    )
                    if (shouldAcceptPulledAlert(alert, queueEngine.summaryVector(), syncedIds)) {
                        val enqueueRes = queueEngine.enqueueOrUpdate(alert, now)
                        if (enqueueRes is EnqueueResult.Enqueued) {
                            database.messageDao().upsertMessage(alert)
                            markSynced(alert.id)
                            EmergencyAlertNotifier.notifyCriticalAlert(appContext, alert)
                            transport.broadcastPacketNow(alert)
                            database.logDao().insertLog(
                                MeshLogEntity(
                                    packetId = alert.id,
                                    packetType = "ALERT",
                                    origin = alert.senderId,
                                    event = "RECEIVED",
                                    hopCount = alert.hopCount,
                                    latencyMs = (now - alert.createdAt).coerceAtLeast(0L),
                                    batteryPct = batteryPct,
                                    scanCycleMs = scanCycleMs,
                                    timestamp = now
                                )
                            )
                            pulled++
                        }
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "Firebase gateway sync error: ${e.message}")
            }

            CloudSyncSummary(online = true, configured = true, pushedCount = pushed, pulledAlerts = pulled)
        }

    @Synchronized
    private fun markSynced(packetId: String) {
        syncedIds.add(packetId)
        prefs.edit().putStringSet(KEY_SYNCED_IDS, LinkedHashSet(syncedIds)).apply()
    }

    companion object {
        private const val TAG = "FirebaseMeshGateway"
        private const val PREFS_GATEWAY = "sos_mesh_firebase_gateway"
        private const val KEY_SYNCED_IDS = "synced_packet_ids"
        const val COLLECTION_PACKETS = "sos_mesh_packets"
        const val COLLECTION_ALERTS = "sos_mesh_alerts"

        fun filterUnsyncedForUpload(
            queue: List<MeshMessageEntity>,
            alreadySyncedIds: Set<String>
        ): List<MeshMessageEntity> {
            return queue.filter { !alreadySyncedIds.contains(it.id) }
        }

        fun shouldAcceptPulledAlert(
            alert: MeshMessageEntity,
            localSummaryVector: Set<String>,
            alreadySyncedIds: Set<String>
        ): Boolean {
            if (alert.type != "ALERT") return false
            if (localSummaryVector.contains(alert.id) || alreadySyncedIds.contains(alert.id)) {
                return false
            }
            return AlertSignatureVerifier.verifyAlert(alert)
        }
    }
}

data class CloudSyncSummary(
    val online: Boolean,
    val configured: Boolean,
    val pushedCount: Int,
    val pulledAlerts: Int
)
