package com.waqar.institute.biometric

import android.content.Context
import androidx.annotation.Keep
import com.example.data.model.StaffMember
import com.google.firebase.Timestamp
import com.google.firebase.firestore.SetOptions
import com.waqar.institute.session.ConcurrentSessionManager
import kotlinx.coroutines.tasks.await
import org.lsposed.lsparanoid.Obfuscate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Automated Teacher Present/Absent Attendance Sync Engine (`TeacherAttendanceSyncManager.kt`).
 *
 * - Immediately logs "PRESENT" in Cloud Firestore (`attendance/teachers_{date}_{uid}` using the
 *   strict 12-key `isValidAttendanceRecord` schema, and `teacherAttendance/{date}_{uid}`) when a
 *   Teacher passes the 24-Hour Face Biometric Gate.
 * - Automatically marks unverified teachers as "ABSENT" after the configurable daily cutoff time
 *   so the Admin Portal Attendance Telemetry Dashboard reflects real-time compliance.
 */
@Obfuscate
@Keep
object TeacherAttendanceSyncManager {

    private fun currentIsoDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun currentReadableTime(): String =
        SimpleDateFormat("hh:mm:ss a", Locale.US).format(Date())

    sealed class TeacherAttendanceSyncResult {
        data class Synced(val docId: String) : TeacherAttendanceSyncResult()
        data class QueuedOffline(val docId: String, val reason: String) : TeacherAttendanceSyncResult()
        data class Failed(val reason: String) : TeacherAttendanceSyncResult()
    }

    suspend fun markTeacherPresentOnBiometricPass(
        context: Context,
        teacherUid: String,
        teacherName: String,
        cosineSimilarity: Float,
        livenessConfidence: Float
    ): TeacherAttendanceSyncResult {
        val cleanUid = teacherUid.trim()
        if (cleanUid.isBlank() || cleanUid.equals("teacher", ignoreCase = true) || cleanUid.equals("default_teacher", ignoreCase = true)) {
            return TeacherAttendanceSyncResult.Failed("Invalid or missing Teacher ID; attendance not logged.")
        }
        if (com.waqar.institute.session.SessionManager.sessionForceLogoutEvent.value != null) {
            return TeacherAttendanceSyncResult.Failed("Active session has been revoked; re-login required.")
        }

        val dateIso = currentIsoDate()
        val timeStr = currentReadableTime()
        val docId = "teachers_${dateIso}_$cleanUid".take(80)
        val teacherAttendanceDocId = "${dateIso}_$cleanUid".take(80)
        val hardwareId = ConcurrentSessionManager.resolveHardwareDeviceId(context)
        val nowTs = Timestamp.now()

        val remarks = "Cosine=${"%.4f".format(Locale.US, cosineSimilarity)} • Liveness=${(livenessConfidence * 100).toInt()}% • $timeStr"
        val attendanceDocPayload = mapOf(
            "id" to docId,
            "targetType" to "STAFF",
            "targetId" to cleanUid,
            "targetName" to teacherName.ifBlank { "Faculty" },
            "batchId" to "FACULTY_24H_GATE",
            "batchName" to "FACULTY_BIOMETRIC_24H_GATE",
            "dateIso" to dateIso,
            "status" to "PRESENT",
            "method" to "BIOMETRIC",
            "remarks" to remarks,
            "markedByUid" to cleanUid,
            "createdAt" to nowTs
        )

        val teacherAttendancePayload = mapOf(
            "id" to teacherAttendanceDocId,
            "teacherId" to cleanUid,
            "teacherUid" to cleanUid,
            "teacherName" to teacherName,
            "dateIso" to dateIso,
            "date" to dateIso,
            "checkInTimeIso" to timeStr,
            "checkOutTimeIso" to "",
            "status" to "PRESENT",
            "method" to "MLKIT_FACE_COSINE_24H_GATE",
            "livenessScore" to livenessConfidence,
            "livenessConfidence" to livenessConfidence,
            "cosineSimilarity" to cosineSimilarity,
            "faceVectorHash" to "cos_${"%.4f".format(Locale.US, cosineSimilarity)}",
            "deviceFingerprint" to hardwareId,
            "hardwareDeviceId" to hardwareId,
            "verifiedAtTime" to timeStr,
            "isAutoAbsentCutoff" to false,
            "verifiedByCamera" to true,
            "createdAt" to nowTs,
            "timestamp" to System.currentTimeMillis()
        )

        return try {
            val db = com.example.data.firestore.InstituteRepository.getConfiguredFirestore(context)
            kotlinx.coroutines.withTimeout(8_000L) {
                db.collection("attendance").document(docId).set(attendanceDocPayload, SetOptions.merge()).await()
                db.collection("teacherAttendance").document(teacherAttendanceDocId).set(teacherAttendancePayload, SetOptions.merge()).await()
            }
            TeacherAttendanceSyncResult.Synced(docId)
        } catch (e: Exception) {
            if (e is com.google.firebase.firestore.FirebaseFirestoreException &&
                e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
            ) {
                return TeacherAttendanceSyncResult.Failed("Cloud permission denied: ${e.localizedMessage ?: "PERMISSION_DENIED"}")
            }
            // Offline or timeout fallback: persist idempotently in Room + WorkManager retry queue
            val queued = runCatching {
                com.waqar.institute.data.repository.OfflineFirstRepository(context.applicationContext)
                    .recordIdempotentAttendance(
                        targetType = "STAFF",
                        targetId = cleanUid,
                        targetName = teacherName.ifBlank { "Faculty" },
                        batchId = "FACULTY_24H_GATE",
                        batchName = "FACULTY_BIOMETRIC_24H_GATE",
                        dateIso = dateIso,
                        status = "PRESENT",
                        method = "BIOMETRIC",
                        remarks = remarks,
                        markedByUid = cleanUid
                    )
                com.waqar.institute.data.sync.IdempotentSyncManager.enqueueIdempotentMutation(
                    collectionPath = "teacherAttendance",
                    documentId = teacherAttendanceDocId,
                    payload = teacherAttendancePayload
                )
                com.waqar.institute.workers.InstituteBackgroundSyncWorker.triggerImmediateSyncNow(context.applicationContext)
            }
            if (queued.isSuccess) {
                TeacherAttendanceSyncResult.QueuedOffline(
                    docId = docId,
                    reason = e.localizedMessage ?: "Network unavailable; queued for auto-sync"
                )
            } else {
                TeacherAttendanceSyncResult.Failed(
                    e.localizedMessage ?: "Failed to save or queue teacher attendance"
                )
            }
        }
    }

    /**
     * Evaluates whether the daily cutoff time (default 10:30 AM) has passed and auto-marks
     * any teacher without a "PRESENT" biometric record today as "ABSENT" in Cloud Firestore.
     */
    suspend fun evaluateDailyCutoffAndAutoMarkAbsent(
        teachers: List<StaffMember>,
        cutoffHour24: Int = 10,
        cutoffMinute: Int = 30,
        actorUid: String = "admin_cutoff"
    ): Int {
        val nowCal = Calendar.getInstance()
        val currentMinutes = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
        val cutoffMinutes = cutoffHour24 * 60 + cutoffMinute
        if (currentMinutes < cutoffMinutes || teachers.isEmpty()) return 0

        val dateIso = currentIsoDate()
        val db = com.example.data.firestore.InstituteRepository.getConfiguredFirestore()
        var autoMarkedAbsentCount = 0

        for (teacher in teachers) {
            val cleanUid = teacher.id.trim().ifBlank { continue }
            val docId = "teachers_${dateIso}_$cleanUid"
            val existing = runCatching {
                db.collection("attendance").document(docId).get().await()
            }.getOrNull()

            if (existing == null || !existing.exists()) {
                val nowTs = Timestamp.now()
                val remarks = "Auto-marked ABSENT: 24-Hour Face Biometric Gate not verified before ${"%02d:%02d".format(Locale.US, cutoffHour24, cutoffMinute)} cutoff"
                val absentAttendancePayload = mapOf(
                    "id" to docId,
                    "targetType" to "STAFF",
                    "targetId" to cleanUid,
                    "targetName" to teacher.fullName.ifBlank { "Faculty" },
                    "batchId" to "FACULTY_24H_GATE",
                    "batchName" to "FACULTY_BIOMETRIC_24H_GATE",
                    "dateIso" to dateIso,
                    "status" to "ABSENT",
                    "method" to "MANUAL",
                    "remarks" to remarks,
                    "markedByUid" to actorUid,
                    "createdAt" to nowTs
                )

                val absentTeacherPayload = mapOf(
                    "id" to "${dateIso}_$cleanUid",
                    "teacherId" to cleanUid,
                    "teacherUid" to cleanUid,
                    "teacherName" to teacher.fullName,
                    "dateIso" to dateIso,
                    "date" to dateIso,
                    "checkInTimeIso" to "",
                    "checkOutTimeIso" to "",
                    "status" to "ABSENT",
                    "method" to "AUTO_CUTOFF_BIOMETRIC_UNVERIFIED",
                    "livenessScore" to 0.0f,
                    "faceVectorHash" to "",
                    "deviceFingerprint" to "",
                    "remarks" to remarks,
                    "isAutoAbsentCutoff" to true,
                    "verifiedByCamera" to false,
                    "createdAt" to nowTs,
                    "timestamp" to System.currentTimeMillis()
                )

                runCatching {
                    db.collection("attendance").document(docId).set(absentAttendancePayload, SetOptions.merge()).await()
                    db.collection("teacherAttendance").document("${dateIso}_$cleanUid").set(absentTeacherPayload, SetOptions.merge()).await()
                    autoMarkedAbsentCount++
                }
            }
        }
        return autoMarkedAbsentCount
    }
}
