package com.focusgate.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
// ENTITIES
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single focus session record.
 */
@Entity(tableName = "focus_sessions")
data class FocusSession(
    @PrimaryKey val id: String,
    val intent: String,
    val startTime: Long,
    val endTime: Long = 0L,
    val durationMinutes: Int = 0,
    val isWorkMode: Boolean = false,
    val whitelistedApps: String = "", // comma-separated
    val nudgeCount: Int = 0,
    val completed: Boolean = false,
    val deviations: String = "" // JSON array of detected deviations
)

/**
 * A captured notification during Work Mode.
 */
@Entity(tableName = "captured_notifications")
data class CapturedNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val importance: Int = 0,
    val isCall: Boolean = false,
    val summarized: Boolean = false,
    val aiCategory: String = "" // "urgent", "social", "work", "spam", etc.
)

/**
 * A deviation event (app mismatch during gate mode).
 */
@Entity(tableName = "deviations")
data class DeviationEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val statedIntent: String,
    val actualApp: String,
    val actualAppName: String,
    val timestamp: Long,
    val nudgeLevel: Int,
    val aiAnalysis: String = "" // AI reasoning for mismatch
)

// ─────────────────────────────────────────────────────────────────────────────
// DAOs
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface FocusSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: FocusSession)

    @Update
    suspend fun update(session: FocusSession)

    @Query("SELECT * FROM focus_sessions WHERE id = :id")
    suspend fun getById(id: String): FocusSession?

    @Query("SELECT * FROM focus_sessions ORDER BY startTime DESC LIMIT 50")
    fun getAllSessions(): Flow<List<FocusSession>>

    @Query("SELECT * FROM focus_sessions WHERE isWorkMode = 1 ORDER BY startTime DESC LIMIT 20")
    fun getWorkModeSessions(): Flow<List<FocusSession>>

    @Query("SELECT * FROM focus_sessions WHERE startTime >= :since ORDER BY startTime DESC")
    suspend fun getSessionsSince(since: Long): List<FocusSession>

    @Query("SELECT SUM(durationMinutes) FROM focus_sessions WHERE completed = 1")
    suspend fun getTotalFocusMinutes(): Long?

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE completed = 1")
    suspend fun getCompletedCount(): Int

    @Query("DELETE FROM focus_sessions WHERE startTime < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface CapturedNotificationDao {
    @Insert
    suspend fun insert(notification: CapturedNotification)

    @Query("SELECT * FROM captured_notifications WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    suspend fun getForSession(sessionId: String): List<CapturedNotification>

    @Query("SELECT * FROM captured_notifications WHERE sessionId = :sessionId AND isCall = 1")
    suspend fun getCallsForSession(sessionId: String): List<CapturedNotification>

    @Query("UPDATE captured_notifications SET summarized = 1, aiCategory = :category WHERE id = :id")
    suspend fun markSummarized(id: Long, category: String)

    @Query("DELETE FROM captured_notifications WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("DELETE FROM captured_notifications WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface DeviationEventDao {
    @Insert
    suspend fun insert(event: DeviationEvent)

    @Query("SELECT * FROM deviations WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getForSession(sessionId: String): List<DeviationEvent>

    @Query("SELECT COUNT(*) FROM deviations WHERE timestamp >= :since")
    suspend fun countSince(since: Long): Int
}

// ─────────────────────────────────────────────────────────────────────────────
// DATABASE
// ─────────────────────────────────────────────────────────────────────────────

@Database(
    entities = [FocusSession::class, CapturedNotification::class, DeviationEvent::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun capturedNotificationDao(): CapturedNotificationDao
    abstract fun deviationEventDao(): DeviationEventDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "focusgate_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REPOSITORY
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Single source of truth for all FocusGate data operations.
 */
class FocusRepository(context: android.content.Context) {
    private val db = AppDatabase.getInstance(context)
    private val sessionDao = db.focusSessionDao()
    private val notifDao = db.capturedNotificationDao()
    private val deviationDao = db.deviationEventDao()

    val allSessions: Flow<List<FocusSession>> = sessionDao.getAllSessions()
    val workModeSessions: Flow<List<FocusSession>> = sessionDao.getWorkModeSessions()

    suspend fun saveSession(session: FocusSession) = sessionDao.insert(session)
    suspend fun updateSession(session: FocusSession) = sessionDao.update(session)
    suspend fun getSession(id: String) = sessionDao.getById(id)

    suspend fun captureNotification(notif: CapturedNotification) = notifDao.insert(notif)
    suspend fun getNotificationsForSession(sessionId: String) = notifDao.getForSession(sessionId)

    suspend fun recordDeviation(event: DeviationEvent) = deviationDao.insert(event)
    suspend fun getDeviationsForSession(sessionId: String) = deviationDao.getForSession(sessionId)

    suspend fun cleanOldData() {
        val cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000) // 30 days
        sessionDao.deleteOlderThan(cutoff)
        notifDao.deleteOlderThan(cutoff)
    }
}
