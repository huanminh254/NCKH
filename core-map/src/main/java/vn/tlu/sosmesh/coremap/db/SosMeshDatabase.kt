package vn.tlu.sosmesh.coremap.db

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Entity(tableName = "messages")
data class MeshMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "type")
    val type: String, // SOS, ALERT, CHAT, ACK_STATUS
    @ColumnInfo(name = "sender_id")
    val senderId: String,
    @ColumnInfo(name = "sender_name")
    val senderName: String,
    @ColumnInfo(name = "latitude")
    val latitude: Double,
    @ColumnInfo(name = "longitude")
    val longitude: Double,
    @ColumnInfo(name = "people_count")
    val peopleCount: Int,
    @ColumnInfo(name = "condition_summary")
    val conditionSummary: String,
    @ColumnInfo(name = "urgency_level")
    val urgencyLevel: String, // CRITICAL, HIGH, MEDIUM
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "signature")
    val signature: String?,
    @ColumnInfo(name = "ttl")
    val ttl: Int,
    @ColumnInfo(name = "hop_count")
    val hopCount: Int,
    @ColumnInfo(name = "status")
    val status: String, // QUEUED, BROADCASTING, DELIVERED, APPROACHING, REACHED
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(tableName = "peers")
data class EncounteredPeerEntity(
    @PrimaryKey
    @ColumnInfo(name = "endpoint_id")
    val endpointId: String,
    @ColumnInfo(name = "device_name")
    val deviceName: String,
    @ColumnInfo(name = "android_api")
    val androidApi: Int,
    @ColumnInfo(name = "supports_ble")
    val supportsBle: Boolean,
    @ColumnInfo(name = "supports_wifi_direct")
    val supportsWifiDirect: Boolean,
    @ColumnInfo(name = "last_rssi")
    val lastRssi: Int,
    @ColumnInfo(name = "exchanged_packets")
    val exchangedPackets: Int,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long
)

@Entity(tableName = "logs")
data class MeshLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "log_id")
    val logId: Long = 0L,
    @ColumnInfo(name = "packet_id")
    val packetId: String,
    @ColumnInfo(name = "packet_type")
    val packetType: String,
    @ColumnInfo(name = "origin", defaultValue = "")
    val origin: String = "",
    @ColumnInfo(name = "event")
    val event: String, // SENT, RECEIVED, FORWARDED, ACK
    @ColumnInfo(name = "hop_count")
    val hopCount: Int,
    @ColumnInfo(name = "latency_ms")
    val latencyMs: Long,
    @ColumnInfo(name = "battery_pct")
    val batteryPct: Int,
    @ColumnInfo(name = "scan_cycle_ms")
    val scanCycleMs: Long,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long
)

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,
    @ColumnInfo(name = "active_mode")
    val activeMode: String, // CITIZEN, RESCUE, OFFICIAL
    @ColumnInfo(name = "battery_saver_enabled")
    val batterySaverEnabled: Boolean
)

@Dao
interface MeshMessageDao {
    @Query("SELECT * FROM messages ORDER BY created_at DESC")
    fun observeAllMessages(): Flow<List<MeshMessageEntity>>

    @Query("SELECT * FROM messages ORDER BY created_at DESC")
    suspend fun getAllMessages(): List<MeshMessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MeshMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MeshMessageEntity)

    @Query("UPDATE messages SET status = :newStatus, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, newStatus: String, updatedAt: Long)

    @Query("DELETE FROM messages WHERE (:nowMs - created_at) > :maxAgeMs")
    suspend fun deleteExpiredMessages(nowMs: Long, maxAgeMs: Long): Int
}

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY last_seen_at DESC")
    fun observeAllPeers(): Flow<List<EncounteredPeerEntity>>

    @Query("SELECT * FROM peers ORDER BY last_seen_at DESC")
    suspend fun getAllPeers(): List<EncounteredPeerEntity>

    @Query("SELECT * FROM peers WHERE endpoint_id = :endpointId LIMIT 1")
    suspend fun getPeerById(endpointId: String): EncounteredPeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: EncounteredPeerEntity)
}

@Dao
interface MeshLogDao {
    @Query("SELECT * FROM logs ORDER BY timestamp DESC")
    fun observeAllLogs(): Flow<List<MeshLogEntity>>

    @Query("SELECT * FROM logs ORDER BY timestamp DESC")
    suspend fun getAllLogs(): List<MeshLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: MeshLogEntity): Long
}

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile LIMIT 1")
    fun observeProfile(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile LIMIT 1")
    suspend fun getProfile(): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProfile(profile: UserProfileEntity)
}

@Database(
    entities = [
        MeshMessageEntity::class,
        EncounteredPeerEntity::class,
        MeshLogEntity::class,
        UserProfileEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class SosMeshDatabase : RoomDatabase() {
    abstract fun messageDao(): MeshMessageDao
    abstract fun peerDao(): PeerDao
    abstract fun logDao(): MeshLogDao
    abstract fun userProfileDao(): UserProfileDao

    val messagesFlow: Flow<List<MeshMessageEntity>>
        get() = messageDao().observeAllMessages()

    val peersFlow: Flow<List<EncounteredPeerEntity>>
        get() = peerDao().observeAllPeers()

    val logsFlow: Flow<List<MeshLogEntity>>
        get() = logDao().observeAllLogs()

    fun observeProfileOrDefault(context: Context): Flow<UserProfileEntity> {
        val fallback = createInitialDeviceProfile(context.applicationContext)
        return userProfileDao().observeProfile().map { it ?: fallback }
    }

    suspend fun getOrCreateProfile(context: Context): UserProfileEntity = withContext(Dispatchers.IO) {
        val existing = userProfileDao().getProfile()
        if (existing != null) {
            existing
        } else {
            val initial = createInitialDeviceProfile(context.applicationContext)
            userProfileDao().saveProfile(initial)
            initial
        }
    }

    suspend fun exportLogsToCsv(context: Context, scenario: String = "K2-K3"): File = withContext(Dispatchers.IO) {
        val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "thu-nghiem/$dateFolder/$scenario"
        )
        dir.mkdirs()
        val csvFile = File(dir, "sos_mesh_logs.csv")
        val logs = logDao().getAllLogs()
        val sb = StringBuilder()
        sb.appendLine("log_id,packet_id,packet_type,origin,event,hop_count,latency_ms,battery_pct,scan_cycle_ms,timestamp")
        for (l in logs) {
            sb.appendLine(
                "${l.logId},${l.packetId},${l.packetType},${l.origin},${l.event},${l.hopCount},${l.latencyMs},${l.batteryPct},${l.scanCycleMs},${l.timestamp}"
            )
        }
        csvFile.writeText(sb.toString(), Charsets.UTF_8)
        csvFile
    }

    companion object {
        private const val DB_NAME = "sos_mesh_v1.db"
        private const val PREFS_NAME = "sos_mesh_node_identity"
        private const val KEY_NODE_ID = "node_id"
        private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `messages`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `sender_id` TEXT NOT NULL, `sender_name` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `people_count` INTEGER NOT NULL, `condition_summary` TEXT NOT NULL, `urgency_level` TEXT NOT NULL, `content` TEXT NOT NULL, `signature` TEXT, `ttl` INTEGER NOT NULL, `hop_count` INTEGER NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("DROP TABLE IF EXISTS `peers`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `peers` (`endpoint_id` TEXT NOT NULL, `device_name` TEXT NOT NULL, `android_api` INTEGER NOT NULL, `supports_ble` INTEGER NOT NULL, `supports_wifi_direct` INTEGER NOT NULL, `last_rssi` INTEGER NOT NULL, `exchanged_packets` INTEGER NOT NULL, `last_seen_at` INTEGER NOT NULL, PRIMARY KEY(`endpoint_id`))"
                )
                db.execSQL("DROP TABLE IF EXISTS `logs`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `logs` (`log_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packet_id` TEXT NOT NULL, `packet_type` TEXT NOT NULL, `origin` TEXT NOT NULL DEFAULT '', `event` TEXT NOT NULL, `hop_count` INTEGER NOT NULL, `latency_ms` INTEGER NOT NULL, `battery_pct` INTEGER NOT NULL, `scan_cycle_ms` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)"
                )
                db.execSQL("DROP TABLE IF EXISTS `user_profile`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_profile` (`user_id` TEXT NOT NULL, `display_name` TEXT NOT NULL, `phone_number` TEXT NOT NULL, `active_mode` TEXT NOT NULL, `battery_saver_enabled` INTEGER NOT NULL, PRIMARY KEY(`user_id`))"
                )
            }
        }

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
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SosMeshDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { db ->
                        instance = db
                        initScope.launch {
                            db.getOrCreateProfile(context.applicationContext)
                        }
                    }
            }
        }
    }
}
