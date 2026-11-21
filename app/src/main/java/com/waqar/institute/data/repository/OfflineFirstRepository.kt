package com.waqar.institute.data.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.example.data.local.CachedStudentEntity
import com.example.data.local.CachedTimetableSlotEntity
import com.example.data.local.InstituteLocalDatabase
import com.example.data.local.QueuedAttendanceEntity
import com.example.data.model.BatchRecord
import com.example.data.model.StudentRecord
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Offline-First Data Caching & Idempotent Transactions Repository (`OfflineFirstRepository.kt`).
 *
 * Bridges the 256-bit AES SQLCipher-encrypted local Room database (`InstituteLocalDatabase`)
 * with Cloud Firestore so all 4 portals (Admin, Teacher, Student, Parent) function seamlessly
 * offline and reconcile mutations idempotently when network connectivity resumes.
 */
class OfflineFirstRepository(
    context: Context,
    private val firestore: FirebaseFirestore = com.example.data.firestore.InstituteRepository.getConfiguredFirestore(context),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val localDb = InstituteLocalDatabase.getDatabase(context.applicationContext ?: context)
    private val offlineDao = localDb.offlineDao()

    fun observeQueuedOfflineAttendance(): Flow<List<QueuedAttendanceEntity>> =
        offlineDao.observeQueuedAttendance()

    fun observeCachedTimetableSlots(): Flow<List<CachedTimetableSlotEntity>> =
        offlineDao.observeCachedTimetable()

    fun pagedCachedStudents(searchQuery: String, pageSize: Int = 40): Flow<PagingData<CachedStudentEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = pageSize,
                prefetchDistance = 15,
                enablePlaceholders = false,
                initialLoadSize = pageSize * 2
            ),
            pagingSourceFactory = { offlineDao.pagingStudents(searchQuery.trim()) }
        ).flow
    }

    /**
     * Generates a deterministic SHA-256 idempotency key so offline retries never create
     * duplicate attendance records, fee receipts, or exam submissions.
     */
    fun computeIdempotencyId(prefix: String, vararg parts: String): String {
        val raw = parts.joinToString("|") { it.trim() }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val hex = digest.take(12).joinToString("") { "%02x".format(it) }
        return "${prefix}_$hex"
    }

    suspend fun cacheStudentsLocally(students: List<StudentRecord>) = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        val entities = students.map { s ->
            CachedStudentEntity(
                id = s.id,
                enrollmentNo = s.enrollmentNo,
                fullName = s.fullName,
                email = s.email,
                phone = s.phone,
                guardianName = s.guardianName,
                guardianPhone = s.guardianPhone,
                courseName = s.courseName,
                batchName = s.batchName,
                status = s.status,
                totalFeePaise = s.totalFeePaise,
                paidFeePaise = s.paidFeePaise,
                attendancePercent = s.attendancePercent,
                photoUri = s.photoUri,
                attachmentName = s.attachmentName,
                updatedAtMillis = now
            )
        }
        offlineDao.upsertCachedStudents(entities)
    }

    suspend fun cacheTimetableSlotsLocally(batches: List<BatchRecord>) = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        val slots = batches.map { b ->
            CachedTimetableSlotEntity(
                batchId = b.id,
                code = b.code,
                name = b.name,
                courseName = b.courseName,
                teacherName = b.teacherName,
                roomName = b.roomName,
                daysOfWeek = b.daysOfWeek,
                startTime = b.startTime,
                endTime = b.endTime,
                updatedAtMillis = now
            )
        }
        offlineDao.upsertTimetableSlots(slots)
    }

    /**
     * Enqueues an attendance record into the SQLCipher offline table with a deterministic
     * idempotency key and attempts an immediate idempotent Firestore write.
     */
    suspend fun recordIdempotentAttendance(
        targetType: String,
        targetId: String,
        targetName: String,
        batchId: String,
        batchName: String,
        dateIso: String,
        status: String,
        method: String,
        remarks: String,
        markedByUid: String
    ): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val prefix = if (targetType.equals("STAFF", ignoreCase = true)) "teachers" else "students"
            val cleanTarget = targetId.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .ifBlank { targetName.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "user" } }
            val deterministicId = "${prefix}_${dateIso.trim()}_$cleanTarget".take(80)
            val entity = QueuedAttendanceEntity(
                id = deterministicId,
                targetType = targetType,
                targetId = targetId,
                targetName = targetName,
                batchId = batchId,
                batchName = batchName,
                dateIso = dateIso,
                status = status,
                method = method,
                remarks = remarks,
                markedByUid = markedByUid,
                queuedAtMillis = System.currentTimeMillis(),
                synced = false
            )
            offlineDao.upsertQueuedAttendance(entity)

            runCatching {
                val payload = mapOf(
                    "id" to deterministicId,
                    "targetType" to targetType,
                    "targetId" to targetId,
                    "targetName" to targetName,
                    "batchId" to batchId,
                    "batchName" to batchName,
                    "dateIso" to dateIso,
                    "status" to status,
                    "method" to method,
                    "remarks" to remarks,
                    "markedByUid" to markedByUid,
                    "createdAt" to Timestamp.now()
                )
                firestore.collection("attendance")
                    .document(deterministicId)
                    .set(payload, SetOptions.merge())
                    .await()
                offlineDao.markAttendanceSynced(deterministicId)
            }
            deterministicId
        }
    }

    /**
     * Flushes all unsynced offline attendance records in an atomic Firestore WriteBatch.
     */
    suspend fun flushUnsyncedOfflineQueue(): Int = withContext(ioDispatcher) {
        val pending = offlineDao.getUnsyncedAttendance()
        if (pending.isEmpty()) return@withContext 0
        var syncedCount = 0
        runCatching {
            val batch = firestore.batch()
            pending.forEach { item ->
                val docRef = firestore.collection("attendance").document(item.id)
                val payload = mapOf(
                    "id" to item.id,
                    "targetType" to item.targetType,
                    "targetId" to item.targetId,
                    "targetName" to item.targetName,
                    "batchId" to item.batchId,
                    "batchName" to item.batchName,
                    "dateIso" to item.dateIso,
                    "status" to item.status,
                    "method" to item.method,
                    "remarks" to item.remarks,
                    "markedByUid" to item.markedByUid,
                    "createdAt" to Timestamp.now()
                )
                batch.set(docRef, payload, SetOptions.merge())
            }
            batch.commit().await()
            pending.forEach { item ->
                offlineDao.markAttendanceSynced(item.id)
                syncedCount++
            }
            offlineDao.clearSyncedAttendance()
        }
        syncedCount
    }
}
