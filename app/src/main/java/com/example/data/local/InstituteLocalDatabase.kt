package com.example.data.local

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.security.HardwareKeystoreVault
import kotlinx.coroutines.flow.Flow
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Entity(tableName = "offline_attendance_queue")
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

@Entity(tableName = "cached_timetable_slots")
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

@Entity(tableName = "cached_students")
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
    version = 2,
    exportSchema = false
)
abstract class InstituteLocalDatabase : RoomDatabase() {
    abstract fun offlineDao(): OfflineInstituteDao

    companion object {
        @Volatile
        private var INSTANCE: InstituteLocalDatabase? = null

        fun getDatabase(context: Context): InstituteLocalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE?.let { return@synchronized it }
                val appCtx = context.applicationContext ?: context

                // Attempt 256-Bit AES SQLCipher Encryption backed by AndroidKeyStore / StrongBox,
                // with automatic fallback to standard Room SQLite if the device kernel (e.g. 16KB page size
                // on Android 15/16) or OEM Keystore rejects libsqlcipher.so.
                val instance = runCatching {
                    SQLiteDatabase.loadLibs(appCtx)
                    val passphraseBytes = HardwareKeystoreVault.getOrCreateSqlCipherPassphrase(appCtx)
                    val supportFactory = SupportFactory(passphraseBytes.copyOf(), null, false)
                    val encryptedDb = Room.databaseBuilder(
                        appCtx,
                        InstituteLocalDatabase::class.java,
                        "waqar_institute_encrypted.db"
                    )
                        .openHelperFactory(supportFactory)
                        .fallbackToDestructiveMigration(dropAllTables = true)
                        .allowMainThreadQueries()
                        .build()
                    // Verify that SQLCipher can actually open the writable database without throwing
                    encryptedDb.openHelper.writableDatabase
                    encryptedDb
                }.getOrElse {
                    Room.databaseBuilder(
                        appCtx,
                        InstituteLocalDatabase::class.java,
                        "waqar_institute_local_v2.db"
                    )
                        .fallbackToDestructiveMigration(dropAllTables = true)
                        .allowMainThreadQueries()
                        .build()
                }

                INSTANCE = instance
                instance
            }
        }
    }
}
