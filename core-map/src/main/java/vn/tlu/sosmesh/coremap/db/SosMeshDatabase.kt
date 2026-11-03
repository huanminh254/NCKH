package vn.tlu.sosmesh.coremap.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.provider.Settings
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "messages")
data class MeshMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "type") val type: String, // SOS, ALERT, CHAT, ACK_STATUS
    @ColumnInfo(name = "sender_id") val senderId: String,
    @ColumnInfo(name = "sender_name") val senderName: String,
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    @ColumnInfo(name = "people_count") val peopleCount: Int,
    @ColumnInfo(name = "condition_summary") val conditionSummary: String,
    @ColumnInfo(name = "urgency_level") val urgencyLevel: String, // CRITICAL, HIGH, MEDIUM
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "signature") val signature: String?,
    @ColumnInfo(name = "ttl") val ttl: Int,
    @ColumnInfo(name = "hop_count") val hopCount: Int,
    @ColumnInfo(name = "status") val status: String, // QUEUED, BROADCASTING, DELIVERED, APPROACHING, REACHED
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(tableName = "peers")
data class EncounteredPeerEntity(
    @PrimaryKey val endpointId: String,
    @ColumnInfo(name = "device_name") val deviceName: String,
    @ColumnInfo(name = "android_api") val androidApi: Int,
    @ColumnInfo(name = "supports_ble") val supportsBle: Boolean,
    @ColumnInfo(name = "supports_wifi_direct") val supportsWifiDirect: Boolean,
    @ColumnInfo(name = "last_rssi") val lastRssi: Int,
    @ColumnInfo(name = "exchanged_packets") val exchangedPackets: Int,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long
)

@Entity(tableName = "logs")
data class MeshLogEntity(
    @PrimaryKey(autoGenerate = true) val logId: Long = 0L,
    @ColumnInfo(name = "packet_id") val packetId: String,
    @ColumnInfo(name = "packet_type") val packetType: String,
    @ColumnInfo(name = "event") val event: String,
    @ColumnInfo(name = "hop_count") val hopCount: Int,
    @ColumnInfo(name = "latency_ms") val latencyMs: Long,
    @ColumnInfo(name = "battery_pct") val batteryPct: Int,
    @ColumnInfo(name = "scan_cycle_ms") val scanCycleMs: Long,
    @ColumnInfo(name = "timestamp") val timestamp: Long
)

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val userId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "phone_number") val phoneNumber: String,
    @ColumnInfo(name = "active_mode") val activeMode: String, // CITIZEN, RESCUE, OFFICIAL
    @ColumnInfo(name = "battery_saver_enabled") val batterySaverEnabled: Boolean
)

@Dao
interface MeshMessageDao {
    @Query("SELECT * FROM messages ORDER BY created_at DESC")
    fun getAllMessages(): List<MeshMessageEntity>

    @Insert
    fun upsertMessage(message: MeshMessageEntity)

    @Update
    fun updateStatus(id: String, newStatus: String, updatedAt: Long)
}

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY last_seen_at DESC")
    fun getAllPeers(): List<EncounteredPeerEntity>

    @Insert
    fun upsertPeer(peer: EncounteredPeerEntity)
}

@Dao
interface MeshLogDao {
    @Query("SELECT * FROM logs ORDER BY timestamp DESC")
    fun getAllLogs(): List<MeshLogEntity>

    @Insert
    fun insertLog(log: MeshLogEntity)
}

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile LIMIT 1")
    fun getProfile(): UserProfileEntity

    @Insert
    fun saveProfile(profile: UserProfileEntity)
}

@Database(
    entities = [
        MeshMessageEntity::class,
        EncounteredPeerEntity::class,
        MeshLogEntity::class,
        UserProfileEntity::class
    ],
    version = 1,
    exportSchema = true
)
class SosMeshDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION),
    MeshMessageDao,
    PeerDao,
    MeshLogDao,
    UserProfileDao {

    private val _messagesFlow = MutableStateFlow<List<MeshMessageEntity>>(emptyList())
    val messagesFlow: StateFlow<List<MeshMessageEntity>> = _messagesFlow.asStateFlow()

    private val _peersFlow = MutableStateFlow<List<EncounteredPeerEntity>>(emptyList())
    val peersFlow: StateFlow<List<EncounteredPeerEntity>> = _peersFlow.asStateFlow()

    private val _logsFlow = MutableStateFlow<List<MeshLogEntity>>(emptyList())
    val logsFlow: StateFlow<List<MeshLogEntity>> = _logsFlow.asStateFlow()

    private val appContext = context.applicationContext

    private val _profileFlow = MutableStateFlow(
        createInitialDeviceProfile(appContext)
    )
    val profileFlow: StateFlow<UserProfileEntity> = _profileFlow.asStateFlow()

    init {
        ensureInitialProfilePersisted()
        refreshFlows()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS messages (" +
                "id TEXT PRIMARY KEY, " +
                "type TEXT NOT NULL, " +
                "sender_id TEXT NOT NULL, " +
                "sender_name TEXT NOT NULL, " +
                "latitude REAL NOT NULL, " +
                "longitude REAL NOT NULL, " +
                "people_count INTEGER NOT NULL, " +
                "condition_summary TEXT NOT NULL, " +
                "urgency_level TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "signature TEXT, " +
                "ttl INTEGER NOT NULL, " +
                "hop_count INTEGER NOT NULL, " +
                "status TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS peers (" +
                "endpoint_id TEXT PRIMARY KEY, " +
                "device_name TEXT NOT NULL, " +
                "android_api INTEGER NOT NULL, " +
                "supports_ble INTEGER NOT NULL, " +
                "supports_wifi_direct INTEGER NOT NULL, " +
                "last_rssi INTEGER NOT NULL, " +
                "exchanged_packets INTEGER NOT NULL, " +
                "last_seen_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS logs (" +
                "log_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "packet_id TEXT NOT NULL, " +
                "packet_type TEXT NOT NULL, " +
                "event TEXT NOT NULL, " +
                "hop_count INTEGER NOT NULL, " +
                "latency_ms INTEGER NOT NULL, " +
                "battery_pct INTEGER NOT NULL, " +
                "scan_cycle_ms INTEGER NOT NULL, " +
                "timestamp INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS user_profile (" +
                "user_id TEXT PRIMARY KEY, " +
                "display_name TEXT NOT NULL, " +
                "phone_number TEXT NOT NULL, " +
                "active_mode TEXT NOT NULL, " +
                "battery_saver_enabled INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    private fun ensureInitialProfilePersisted() {
        val hasStoredProfile = readableDatabase.rawQuery("SELECT COUNT(*) FROM user_profile", null).use { c ->
            c.moveToFirst() && c.getInt(0) > 0
        }
        if (!hasStoredProfile) {
            saveProfile(createInitialDeviceProfile(appContext))
        }
    }

    fun refreshFlows() {
        _messagesFlow.value = getAllMessages()
        _peersFlow.value = getAllPeers()
        _logsFlow.value = getAllLogs()
        _profileFlow.value = getProfile()
    }

    override fun getAllMessages(): List<MeshMessageEntity> {
        val list = mutableListOf<MeshMessageEntity>()
        readableDatabase.rawQuery("SELECT * FROM messages ORDER BY created_at DESC", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    MeshMessageEntity(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        type = c.getString(c.getColumnIndexOrThrow("type")),
                        senderId = c.getString(c.getColumnIndexOrThrow("sender_id")),
                        senderName = c.getString(c.getColumnIndexOrThrow("sender_name")),
                        latitude = c.getDouble(c.getColumnIndexOrThrow("latitude")),
                        longitude = c.getDouble(c.getColumnIndexOrThrow("longitude")),
                        peopleCount = c.getInt(c.getColumnIndexOrThrow("people_count")),
                        conditionSummary = c.getString(c.getColumnIndexOrThrow("condition_summary")),
                        urgencyLevel = c.getString(c.getColumnIndexOrThrow("urgency_level")),
                        content = c.getString(c.getColumnIndexOrThrow("content")),
                        signature = c.getString(c.getColumnIndexOrThrow("signature")),
                        ttl = c.getInt(c.getColumnIndexOrThrow("ttl")),
                        hopCount = c.getInt(c.getColumnIndexOrThrow("hop_count")),
                        status = c.getString(c.getColumnIndexOrThrow("status")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
                    )
                )
            }
        }
        return list
    }

    override fun upsertMessage(message: MeshMessageEntity) {
        val cv = ContentValues().apply {
            put("id", message.id)
            put("type", message.type)
            put("sender_id", message.senderId)
            put("sender_name", message.senderName)
            put("latitude", message.latitude)
            put("longitude", message.longitude)
            put("people_count", message.peopleCount)
            put("condition_summary", message.conditionSummary)
            put("urgency_level", message.urgencyLevel)
            put("content", message.content)
            put("signature", message.signature)
            put("ttl", message.ttl)
            put("hop_count", message.hopCount)
            put("status", message.status)
            put("created_at", message.createdAt)
            put("updated_at", message.updatedAt)
        }
        writableDatabase.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        _messagesFlow.value = getAllMessages()
    }

    override fun updateStatus(id: String, newStatus: String, updatedAt: Long) {
        val cv = ContentValues().apply {
            put("status", newStatus)
            put("updated_at", updatedAt)
        }
        writableDatabase.update("messages", cv, "id = ?", arrayOf(id))
        _messagesFlow.value = getAllMessages()
    }

    override fun getAllPeers(): List<EncounteredPeerEntity> {
        val list = mutableListOf<EncounteredPeerEntity>()
        readableDatabase.rawQuery("SELECT * FROM peers ORDER BY last_seen_at DESC", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    EncounteredPeerEntity(
                        endpointId = c.getString(c.getColumnIndexOrThrow("endpoint_id")),
                        deviceName = c.getString(c.getColumnIndexOrThrow("device_name")),
                        androidApi = c.getInt(c.getColumnIndexOrThrow("android_api")),
                        supportsBle = c.getInt(c.getColumnIndexOrThrow("supports_ble")) == 1,
                        supportsWifiDirect = c.getInt(c.getColumnIndexOrThrow("supports_wifi_direct")) == 1,
                        lastRssi = c.getInt(c.getColumnIndexOrThrow("last_rssi")),
                        exchangedPackets = c.getInt(c.getColumnIndexOrThrow("exchanged_packets")),
                        lastSeenAt = c.getLong(c.getColumnIndexOrThrow("last_seen_at"))
                    )
                )
            }
        }
        return list
    }

    override fun upsertPeer(peer: EncounteredPeerEntity) {
        val cv = ContentValues().apply {
            put("endpoint_id", peer.endpointId)
            put("device_name", peer.deviceName)
            put("android_api", peer.androidApi)
            put("supports_ble", if (peer.supportsBle) 1 else 0)
            put("supports_wifi_direct", if (peer.supportsWifiDirect) 1 else 0)
            put("last_rssi", peer.lastRssi)
            put("exchanged_packets", peer.exchangedPackets)
            put("last_seen_at", peer.lastSeenAt)
        }
        writableDatabase.insertWithOnConflict("peers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        _peersFlow.value = getAllPeers()
    }

    override fun getAllLogs(): List<MeshLogEntity> {
        val list = mutableListOf<MeshLogEntity>()
        readableDatabase.rawQuery("SELECT * FROM logs ORDER BY timestamp DESC", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    MeshLogEntity(
                        logId = c.getLong(c.getColumnIndexOrThrow("log_id")),
                        packetId = c.getString(c.getColumnIndexOrThrow("packet_id")),
                        packetType = c.getString(c.getColumnIndexOrThrow("packet_type")),
                        event = c.getString(c.getColumnIndexOrThrow("event")),
                        hopCount = c.getInt(c.getColumnIndexOrThrow("hop_count")),
                        latencyMs = c.getLong(c.getColumnIndexOrThrow("latency_ms")),
                        batteryPct = c.getInt(c.getColumnIndexOrThrow("battery_pct")),
                        scanCycleMs = c.getLong(c.getColumnIndexOrThrow("scan_cycle_ms")),
                        timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"))
                    )
                )
            }
        }
        return list
    }

    override fun insertLog(log: MeshLogEntity) {
        val cv = ContentValues().apply {
            put("packet_id", log.packetId)
            put("packet_type", log.packetType)
            put("event", log.event)
            put("hop_count", log.hopCount)
            put("latency_ms", log.latencyMs)
            put("battery_pct", log.batteryPct)
            put("scan_cycle_ms", log.scanCycleMs)
            put("timestamp", log.timestamp)
        }
        writableDatabase.insert("logs", null, cv)
        _logsFlow.value = getAllLogs()
    }

    override fun getProfile(): UserProfileEntity {
        readableDatabase.rawQuery("SELECT * FROM user_profile LIMIT 1", null).use { c ->
            if (c.moveToFirst()) {
                return UserProfileEntity(
                    userId = c.getString(c.getColumnIndexOrThrow("user_id")),
                    displayName = c.getString(c.getColumnIndexOrThrow("display_name")),
                    phoneNumber = c.getString(c.getColumnIndexOrThrow("phone_number")),
                    activeMode = c.getString(c.getColumnIndexOrThrow("active_mode")),
                    batterySaverEnabled = c.getInt(c.getColumnIndexOrThrow("battery_saver_enabled")) == 1
                )
            }
        }
        return _profileFlow.value
    }

    override fun saveProfile(profile: UserProfileEntity) {
        val cv = ContentValues().apply {
            put("user_id", profile.userId)
            put("display_name", profile.displayName)
            put("phone_number", profile.phoneNumber)
            put("active_mode", profile.activeMode)
            put("battery_saver_enabled", if (profile.batterySaverEnabled) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("user_profile", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        _profileFlow.value = profile
    }

    fun exportLogsToCsv(context: Context): File {
        val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "thu-nghiem/$dateFolder/K2-K3")
        dir.mkdirs()
        val csvFile = File(dir, "sos_mesh_logs.csv")
        val logs = getAllLogs()
        val sb = StringBuilder()
        sb.appendLine("log_id,packet_id,packet_type,event,hop_count,latency_ms,battery_pct,scan_cycle_ms,timestamp")
        for (l in logs) {
            sb.appendLine("${l.logId},${l.packetId},${l.packetType},${l.event},${l.hopCount},${l.latencyMs},${l.batteryPct},${l.scanCycleMs},${l.timestamp}")
        }
        csvFile.writeText(sb.toString(), Charsets.UTF_8)
        return csvFile
    }

    companion object {
        private const val DB_NAME = "sos_mesh_v1.db"
        private const val DB_VERSION = 1
        private const val PREFS_NAME = "sos_mesh_node_identity"
        private const val KEY_NODE_ID = "node_id"

        fun createInitialDeviceProfile(context: Context): UserProfileEntity {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var nodeId = prefs.getString(KEY_NODE_ID, null)
            if (nodeId.isNullOrBlank()) {
                val androidId = runCatching {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                }.getOrNull()?.takeIf { it.isNotBlank() }
                val suffix = (androidId ?: UUID.randomUUID().toString().replace("-", ""))
                    .takeLast(8)
                    .uppercase(Locale.US)
                nodeId = "NODE-$suffix"
                prefs.edit().putString(KEY_NODE_ID, nodeId).apply()
            }
            val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
            val model = Build.MODEL?.trim().orEmpty()
            val deviceLabel = listOf(manufacturer, model)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "Android API ${Build.VERSION.SDK_INT}" }
            val defaultMode = if (context.packageName.contains("rescue", ignoreCase = true)) {
                "RESCUE"
            } else {
                "CITIZEN"
            }
            return UserProfileEntity(
                userId = nodeId,
                displayName = "$deviceLabel ($nodeId)",
                phoneNumber = "",
                activeMode = defaultMode,
                batterySaverEnabled = false
            )
        }

        @Volatile
        private var instance: SosMeshDatabase? = null

        fun getInstance(context: Context): SosMeshDatabase {
            return instance ?: synchronized(this) {
                instance ?: SosMeshDatabase(context).also { instance = it }
            }
        }
    }
}
