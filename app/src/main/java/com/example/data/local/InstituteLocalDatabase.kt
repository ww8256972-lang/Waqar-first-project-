package com.example.data.local

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.security.HardwareKeystoreVault
import java.util.Arrays
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(
    tableName = "offline_attendance_queue",
    indices = [
        Index(value = ["synced", "queuedAtMillis"], name = "idx_attendance_synced_queued"),
        Index(value = ["batchId", "dateIso"], name = "idx_attendance_batch_date")
    ]
)
data class QueuedAttendanceEntity(
    @PrimaryKey val id: String,
    val targetType: String,
    val targetId: String,
    val targetName: String,
    val batchId: String,
    val batchName: String,
    val dateIso: String,
    val status: String,
    val method: String,
    val remarks: String,
    val markedByUid: String,
    val queuedAtMillis: Long,
    val synced: Boolean = false
)

@Entity(
    tableName = "cached_timetable_slots",
    indices = [
        Index(value = ["startTime"], name = "idx_timetable_start_time"),
        Index(value = ["courseName"], name = "idx_timetable_course_name")
    ]
)
data class CachedTimetableSlotEntity(
    @PrimaryKey val batchId: String,
    val code: String,
    val name: String,
    val courseName: String,
    val teacherName: String,
    val roomName: String,
    val daysOfWeek: String,
    val startTime: String,
    val endTime: String,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "cached_students",
    indices = [
        Index(value = ["fullName"], name = "idx_students_full_name"),
        Index(value = ["enrollmentNo"], name = "idx_students_enrollment_no"),
        Index(value = ["courseName"], name = "idx_students_course_name"),
        Index(value = ["batchName"], name = "idx_students_batch_name"),
        Index(value = ["status"], name = "idx_students_status"),
        Index(value = ["updatedAtMillis"], name = "idx_students_updated_at")
    ]
)
data class CachedStudentEntity(
    @PrimaryKey val id: String,
    val enrollmentNo: String,
    val fullName: String,
    val email: String,
    val phone: String,
    val guardianName: String,
    val guardianPhone: String,
    val courseName: String,
    val batchName: String,
    val status: String,
    val totalFeePaise: Long,
    val paidFeePaise: Long,
    val attendancePercent: Int,
    val photoUri: String,
    val attachmentName: String,
    val updatedAtMillis: Long
)

@Dao
interface OfflineInstituteDao {
    @Query("SELECT * FROM offline_attendance_queue ORDER BY queuedAtMillis DESC")
    fun observeQueuedAttendance(): Flow<List<QueuedAttendanceEntity>>

    @Query("SELECT * FROM offline_attendance_queue WHERE synced = 0 ORDER BY queuedAtMillis ASC")
    suspend fun getUnsyncedAttendance(): List<QueuedAttendanceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQueuedAttendance(item: QueuedAttendanceEntity)

    @Query("UPDATE offline_attendance_queue SET synced = 1 WHERE id = :id")
    suspend fun markAttendanceSynced(id: String)

    @Query("DELETE FROM offline_attendance_queue WHERE synced = 1")
    suspend fun clearSyncedAttendance()

    @Query("SELECT * FROM cached_timetable_slots ORDER BY startTime ASC")
    fun observeCachedTimetable(): Flow<List<CachedTimetableSlotEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTimetableSlots(slots: List<CachedTimetableSlotEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedStudents(students: List<CachedStudentEntity>)

    @Query("SELECT COUNT(*) FROM cached_students")
    suspend fun countCachedStudents(): Int

    @Query(
        """
        SELECT * FROM cached_students
        WHERE (:search = '' OR fullName LIKE :search || '%' OR enrollmentNo LIKE :search || '%' OR courseName LIKE :search || '%')
        ORDER BY fullName ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun queryCachedStudentsPage(search: String, limit: Int, offset: Int): List<CachedStudentEntity>

    @Query(
        """
        SELECT * FROM cached_students
        WHERE (:search = '' OR fullName LIKE '%' || :search || '%' OR enrollmentNo LIKE '%' || :search || '%' OR courseName LIKE '%' || :search || '%')
        ORDER BY fullName ASC
        """
    )
    fun pagingStudents(search: String): PagingSource<Int, CachedStudentEntity>
}

@Database(
    entities = [
        QueuedAttendanceEntity::class,
        CachedTimetableSlotEntity::class,
        CachedStudentEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class InstituteLocalDatabase : RoomDatabase() {
    abstract fun offlineDao(): OfflineInstituteDao

    companion object {
        @Volatile
        private var INSTANCE: InstituteLocalDatabase? = null

        @Volatile
        var isSqlCipherActive: Boolean = false
            private set

        @Volatile
        var databaseEncryptionLabel: String = "Initializing"
            private set

        /**
         * Non-destructive migration 1 -> 2: ensures `cached_students` table exists without dropping existing data.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_students` (
                        `id` TEXT NOT NULL,
                        `enrollmentNo` TEXT NOT NULL,
                        `fullName` TEXT NOT NULL,
                        `email` TEXT NOT NULL,
                        `phone` TEXT NOT NULL,
                        `guardianName` TEXT NOT NULL,
                        `guardianPhone` TEXT NOT NULL,
                        `courseName` TEXT NOT NULL,
                        `batchName` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `totalFeePaise` INTEGER NOT NULL,
                        `paidFeePaise` INTEGER NOT NULL,
                        `attendancePercent` INTEGER NOT NULL,
                        `photoUri` TEXT NOT NULL,
                        `attachmentName` TEXT NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Non-destructive migration 2 -> 3: creates B-tree indices for 100,000+ record query scalability
         * while preserving 100% of existing student, timetable, and attendance rows.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_attendance_synced_queued` ON `offline_attendance_queue` (`synced`, `queuedAtMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_attendance_batch_date` ON `offline_attendance_queue` (`batchId`, `dateIso`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_timetable_start_time` ON `cached_timetable_slots` (`startTime`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_timetable_course_name` ON `cached_timetable_slots` (`courseName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_students_full_name` ON `cached_students` (`fullName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_students_enrollment_no` ON `cached_students` (`enrollmentNo`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_students_course_name` ON `cached_students` (`courseName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_students_batch_name` ON `cached_students` (`batchName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_students_status` ON `cached_students` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_students_updated_at` ON `cached_students` (`updatedAtMillis`)")
            }
        }

        fun getDatabase(context: Context): InstituteLocalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE?.let { return@synchronized it }
                val appCtx = context.applicationContext ?: context

                val instance = runCatching {
                    System.loadLibrary("sqlcipher")
                    val passphraseBytes = HardwareKeystoreVault.getOrCreateSqlCipherPassphrase(appCtx)
                    val passphraseCopy = passphraseBytes.copyOf()
                    val supportFactory = SupportOpenHelperFactory(passphraseCopy)
                    val encryptedDb = Room.databaseBuilder(
                        appCtx,
                        InstituteLocalDatabase::class.java,
                        "waqar_institute_encrypted.db"
                    )
                        .openHelperFactory(supportFactory)
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                        .build()
                    // Verify SQLCipher native open succeeds before adopting encrypted instance
                    encryptedDb.openHelper.writableDatabase
                    Arrays.fill(passphraseBytes, 0.toByte())
                    isSqlCipherActive = true
                    databaseEncryptionLabel = "SQLCipher 256-Bit AES (KeyStore Bound)"
                    encryptedDb
                }.getOrElse {
                    isSqlCipherActive = false
                    databaseEncryptionLabel = "Room SQLite (SQLCipher Native Fallback)"
                    Room.databaseBuilder(
                        appCtx,
                        InstituteLocalDatabase::class.java,
                        "waqar_institute_local_v2.db"
                    )
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                        .build()
                }

                INSTANCE = instance
                instance
            }
        }
    }
}
