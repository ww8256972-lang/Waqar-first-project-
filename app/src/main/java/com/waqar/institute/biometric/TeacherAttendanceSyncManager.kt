package com.waqar.institute.biometric

import android.content.Context
import androidx.annotation.Keep
import com.example.data.model.StaffMember
import com.google.firebase.firestore.FirebaseFirestore
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
 * - Immediately logs "PRESENT" in Cloud Firestore (`attendance/teachers_{date}_{uid}` and
 *   `teacherAttendance/{date}_{uid}`) when a Teacher passes the 24-Hour Face Biometric Gate.
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

    suspend fun markTeacherPresentOnBiometricPass(
        context: Context,
        teacherUid: String,
        teacherName: String,
        cosineSimilarity: Float,
        livenessConfidence: Float
    ) {
        val dateIso = currentIsoDate()
        val timeStr = currentReadableTime()
        val cleanUid = teacherUid.trim().ifBlank { "teacher" }
        val docId = "teachers_${dateIso}_$cleanUid"
        val hardwareId = ConcurrentSessionManager.resolveHardwareDeviceId(context)

        val payload = mapOf(
            "id" to docId,
            "targetType" to "TEACHER",
            "teacherUid" to cleanUid,
            "studentId" to cleanUid,
            "studentName" to teacherName,
            "teacherName" to teacherName,
            "batchName" to "FACULTY_BIOMETRIC_24H_GATE",
            "date" to dateIso,
            "status" to "PRESENT",
            "method" to "AADHAAR_FACE_COSINE_24H_GATE",
            "cosineSimilarity" to cosineSimilarity,
            "livenessConfidence" to livenessConfidence,
            "hardwareDeviceId" to hardwareId,
            "verifiedAtTime" to timeStr,
            "timestamp" to System.currentTimeMillis()
        )

        runCatching {
            val db = FirebaseFirestore.getInstance()
            db.collection("attendance").document(docId).set(payload, SetOptions.merge()).await()
            db.collection("teacherAttendance").document("${dateIso}_$cleanUid").set(payload, SetOptions.merge()).await()
        }
    }

    /**
     * Evaluates whether the daily cutoff time (default 10:30 AM) has passed and auto-marks
     * any teacher without a "PRESENT" biometric record today as "ABSENT" in Cloud Firestore.
     */
    suspend fun evaluateDailyCutoffAndAutoMarkAbsent(
        teachers: List<StaffMember>,
        cutoffHour24: Int = 10,
        cutoffMinute: Int = 30
    ): Int {
        val nowCal = Calendar.getInstance()
        val currentMinutes = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
        val cutoffMinutes = cutoffHour24 * 60 + cutoffMinute
        if (currentMinutes < cutoffMinutes || teachers.isEmpty()) return 0

        val dateIso = currentIsoDate()
        val db = FirebaseFirestore.getInstance()
        var autoMarkedAbsentCount = 0

        for (teacher in teachers) {
            val cleanUid = teacher.id.trim().ifBlank { continue }
            val docId = "teachers_${dateIso}_$cleanUid"
            val existing = runCatching {
                db.collection("attendance").document(docId).get().await()
            }.getOrNull()

            if (existing == null || !existing.exists()) {
                val absentPayload = mapOf(
                    "id" to docId,
                    "targetType" to "TEACHER",
                    "teacherUid" to cleanUid,
                    "studentId" to cleanUid,
                    "studentName" to teacher.fullName,
                    "teacherName" to teacher.fullName,
                    "batchName" to "FACULTY_BIOMETRIC_24H_GATE",
                    "date" to dateIso,
                    "status" to "ABSENT",
                    "method" to "AUTO_CUTOFF_BIOMETRIC_UNVERIFIED",
                    "remarks" to "Auto-marked ABSENT: 24-Hour Face Biometric Gate not verified before ${"%02d:%02d".format(cutoffHour24, cutoffMinute)} cutoff",
                    "timestamp" to System.currentTimeMillis()
                )

                runCatching {
                    db.collection("attendance").document(docId).set(absentPayload, SetOptions.merge()).await()
                    db.collection("teacherAttendance").document("${dateIso}_$cleanUid").set(absentPayload, SetOptions.merge()).await()
                    autoMarkedAbsentCount++
                }
            }
        }
        return autoMarkedAbsentCount
    }
}
