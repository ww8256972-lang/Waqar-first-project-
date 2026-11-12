package com.example.data.model

import com.google.firebase.Timestamp

enum class PortalRole(val key: String, val label: String, val hindiLabel: String) {
    ADMIN("admin", "Admin", "एडमिन (Owner)"),
    STUDENT("student", "Student", "छात्र (Student)"),
    TEACHER("teacher", "Teacher", "शिक्षक (Faculty)"),
    PARENT("parent", "Parent", "अभिभावक (Parent)");

    companion object {
        fun fromKey(key: String): PortalRole =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: STUDENT
    }
}

data class UserProfile(
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val role: String = "student",
    val username: String = "",
    val phone: String = "",
    val mfaEnabled: Boolean = false,
    val totpSecret: String = "",
    val linkedEntityId: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "uid" to uid,
        "email" to email,
        "displayName" to displayName,
        "role" to role,
        "username" to username,
        "phone" to phone,
        "mfaEnabled" to mfaEnabled,
        "totpSecret" to totpSecret,
        "linkedEntityId" to linkedEntityId,
        "createdAt" to createdAt
    )
}

data class UsernameAlias(
    val username: String = "",
    val uid: String = "",
    val role: String = "student",
    val email: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "username" to username,
        "uid" to uid,
        "role" to role,
        "email" to email,
        "createdAt" to createdAt
    )
}

data class DeviceSession(
    val sessionId: String = "",
    val uid: String = "",
    val deviceName: String = "",
    val platform: String = "Android",
    val active: Boolean = true,
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "sessionId" to sessionId,
        "uid" to uid,
        "deviceName" to deviceName,
        "platform" to platform,
        "active" to active,
        "createdAt" to createdAt
    )
}

data class PortalCredential(
    val username: String = "",
    val passwordHash: String = "",
    val passwordSalt: String = "",
    val role: String = "student",
    val linkedEntityId: String = "",
    val displayName: String = "",
    val assignedByUid: String = "",
    val active: Boolean = true,
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "username" to username,
        "passwordHash" to passwordHash,
        "passwordSalt" to passwordSalt,
        "role" to role,
        "linkedEntityId" to linkedEntityId,
        "displayName" to displayName,
        "assignedByUid" to assignedByUid,
        "active" to active,
        "createdAt" to createdAt
    )
}

data class StudentRecord(
    val id: String = "",
    val enrollmentNo: String = "",
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val guardianName: String = "",
    val guardianPhone: String = "",
    val courseId: String = "",
    val courseName: String = "",
    val batchId: String = "",
    val batchName: String = "",
    val status: String = "ACTIVE",
    val totalFeePaise: Long = 0L,
    val paidFeePaise: Long = 0L,
    val attendancePercent: Int = 100,
    val photoUri: String = "",
    val attachmentName: String = "",
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    val dueFeePaise: Long get() = (totalFeePaise - paidFeePaise).coerceAtLeast(0L)

    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "enrollmentNo" to enrollmentNo,
        "fullName" to fullName,
        "email" to email,
        "phone" to phone,
        "guardianName" to guardianName,
        "guardianPhone" to guardianPhone,
        "courseId" to courseId,
        "courseName" to courseName,
        "batchId" to batchId,
        "batchName" to batchName,
        "status" to status,
        "totalFeePaise" to totalFeePaise,
        "paidFeePaise" to paidFeePaise,
        "attendancePercent" to attendancePercent,
        "photoUri" to photoUri,
        "attachmentName" to attachmentName,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class AdmissionEnquiry(
    val id: String = "",
    val applicantName: String = "",
    val phone: String = "",
    val email: String = "",
    val interestedCourse: String = "",
    val source: String = "Walk-in",
    val stage: String = "NEW",
    val counsellorName: String = "",
    val followUpDate: String = "",
    val notes: String = "",
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "applicantName" to applicantName,
        "phone" to phone,
        "email" to email,
        "interestedCourse" to interestedCourse,
        "source" to source,
        "stage" to stage,
        "counsellorName" to counsellorName,
        "followUpDate" to followUpDate,
        "notes" to notes,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class CourseRecord(
    val id: String = "",
    val code: String = "",
    val title: String = "",
    val category: String = "",
    val durationMonths: Int = 6,
    val totalFeePaise: Long = 0L,
    val syllabusSummary: String = "",
    val totalModules: Int = 6,
    val active: Boolean = true,
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "code" to code,
        "title" to title,
        "category" to category,
        "durationMonths" to durationMonths,
        "totalFeePaise" to totalFeePaise,
        "syllabusSummary" to syllabusSummary,
        "totalModules" to totalModules,
        "active" to active,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class BatchRecord(
    val id: String = "",
    val code: String = "",
    val name: String = "",
    val courseId: String = "",
    val courseName: String = "",
    val teacherName: String = "",
    val roomName: String = "",
    val daysOfWeek: String = "Mon-Sat",
    val startTime: String = "09:00",
    val endTime: String = "10:30",
    val capacity: Int = 30,
    val enrolledCount: Int = 0,
    val status: String = "ACTIVE",
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "code" to code,
        "name" to name,
        "courseId" to courseId,
        "courseName" to courseName,
        "teacherName" to teacherName,
        "roomName" to roomName,
        "daysOfWeek" to daysOfWeek,
        "startTime" to startTime,
        "endTime" to endTime,
        "capacity" to capacity,
        "enrolledCount" to enrolledCount,
        "status" to status,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class AttendanceRecord(
    val id: String = "",
    val targetType: String = "STUDENT",
    val targetId: String = "",
    val targetName: String = "",
    val batchId: String = "",
    val batchName: String = "",
    val dateIso: String = "",
    val status: String = "PRESENT",
    val method: String = "MANUAL",
    val remarks: String = "",
    val markedByUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
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
        "createdAt" to createdAt
    )
}

data class FeeInvoice(
    val id: String = "",
    val receiptNo: String = "",
    val studentId: String = "",
    val studentName: String = "",
    val courseName: String = "",
    val title: String = "",
    val totalAmountPaise: Long = 0L,
    val paidAmountPaise: Long = 0L,
    val discountPaise: Long = 0L,
    val finePaise: Long = 0L,
    val paymentMode: String = "UPI",
    val status: String = "PAID",
    val dueDateIso: String = "",
    val paidAtIso: String = "",
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    val balancePaise: Long get() = (totalAmountPaise - discountPaise + finePaise - paidAmountPaise).coerceAtLeast(0L)

    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "receiptNo" to receiptNo,
        "studentId" to studentId,
        "studentName" to studentName,
        "courseName" to courseName,
        "title" to title,
        "totalAmountPaise" to totalAmountPaise,
        "paidAmountPaise" to paidAmountPaise,
        "discountPaise" to discountPaise,
        "finePaise" to finePaise,
        "paymentMode" to paymentMode,
        "status" to status,
        "dueDateIso" to dueDateIso,
        "paidAtIso" to paidAtIso,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class LedgerEntry(
    val id: String = "",
    val entryType: String = "EXPENSE",
    val category: String = "",
    val title: String = "",
    val vendorName: String = "",
    val amountPaise: Long = 0L,
    val gstAmountPaise: Long = 0L,
    val paymentMode: String = "BANK_TRANSFER",
    val referenceNo: String = "",
    val dateIso: String = "",
    val approvedBy: String = "",
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "entryType" to entryType,
        "category" to category,
        "title" to title,
        "vendorName" to vendorName,
        "amountPaise" to amountPaise,
        "gstAmountPaise" to gstAmountPaise,
        "paymentMode" to paymentMode,
        "referenceNo" to referenceNo,
        "dateIso" to dateIso,
        "approvedBy" to approvedBy,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class ExamRecord(
    val id: String = "",
    val title: String = "",
    val courseId: String = "",
    val courseName: String = "",
    val batchName: String = "",
    val examType: String = "MCQ_ONLINE",
    val durationMinutes: Int = 60,
    val totalMarks: Int = 100,
    val passingMarks: Int = 40,
    val scheduledDateIso: String = "",
    val questionsJson: String = "[]",
    val status: String = "SCHEDULED",
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "title" to title,
        "courseId" to courseId,
        "courseName" to courseName,
        "batchName" to batchName,
        "examType" to examType,
        "durationMinutes" to durationMinutes,
        "totalMarks" to totalMarks,
        "passingMarks" to passingMarks,
        "scheduledDateIso" to scheduledDateIso,
        "questionsJson" to questionsJson,
        "status" to status,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class ExamResult(
    val id: String = "",
    val examId: String = "",
    val examTitle: String = "",
    val studentId: String = "",
    val studentName: String = "",
    val marksObtained: Int = 0,
    val totalMarks: Int = 100,
    val grade: String = "A",
    val rank: Int = 1,
    val status: String = "PASSED",
    val remarks: String = "",
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "examId" to examId,
        "examTitle" to examTitle,
        "studentId" to studentId,
        "studentName" to studentName,
        "marksObtained" to marksObtained,
        "totalMarks" to totalMarks,
        "grade" to grade,
        "rank" to rank,
        "status" to status,
        "remarks" to remarks,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class StudyMaterial(
    val id: String = "",
    val title: String = "",
    val courseName: String = "",
    val batchName: String = "",
    val materialType: String = "PDF_NOTES",
    val contentUrlOrBody: String = "",
    val dueDateIso: String = "",
    val maxMarks: Int = 100,
    val downloadsCount: Int = 0,
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "title" to title,
        "courseName" to courseName,
        "batchName" to batchName,
        "materialType" to materialType,
        "contentUrlOrBody" to contentUrlOrBody,
        "dueDateIso" to dueDateIso,
        "maxMarks" to maxMarks,
        "downloadsCount" to downloadsCount,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class CertificateRecord(
    val id: String = "",
    val serialNo: String = "",
    val certType: String = "DIPLOMA",
    val studentId: String = "",
    val studentName: String = "",
    val courseName: String = "",
    val grade: String = "A+",
    val issueDateIso: String = "",
    val verificationHash: String = "",
    val revoked: Boolean = false,
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "serialNo" to serialNo,
        "certType" to certType,
        "studentId" to studentId,
        "studentName" to studentName,
        "courseName" to courseName,
        "grade" to grade,
        "issueDateIso" to issueDateIso,
        "verificationHash" to verificationHash,
        "revoked" to revoked,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class StaffMember(
    val id: String = "",
    val employeeCode: String = "",
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val designation: String = "",
    val department: String = "",
    val monthlySalaryPaise: Long = 0L,
    val joiningDateIso: String = "",
    val status: String = "ACTIVE",
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "employeeCode" to employeeCode,
        "fullName" to fullName,
        "email" to email,
        "phone" to phone,
        "designation" to designation,
        "department" to department,
        "monthlySalaryPaise" to monthlySalaryPaise,
        "joiningDateIso" to joiningDateIso,
        "status" to status,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class PayrollEntry(
    val id: String = "",
    val staffId: String = "",
    val staffName: String = "",
    val monthYear: String = "",
    val basicPaise: Long = 0L,
    val allowancesPaise: Long = 0L,
    val deductionsPaise: Long = 0L,
    val netPayPaise: Long = 0L,
    val status: String = "APPROVED",
    val leaveDays: Int = 0,
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "staffId" to staffId,
        "staffName" to staffName,
        "monthYear" to monthYear,
        "basicPaise" to basicPaise,
        "allowancesPaise" to allowancesPaise,
        "deductionsPaise" to deductionsPaise,
        "netPayPaise" to netPayPaise,
        "status" to status,
        "leaveDays" to leaveDays,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class GuardianLink(
    val id: String = "",
    val guardianName: String = "",
    val phone: String = "",
    val email: String = "",
    val relation: String = "Father",
    val studentId: String = "",
    val studentName: String = "",
    val ptmNotes: String = "",
    val consentSigned: Boolean = true,
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "guardianName" to guardianName,
        "phone" to phone,
        "email" to email,
        "relation" to relation,
        "studentId" to studentId,
        "studentName" to studentName,
        "ptmNotes" to ptmNotes,
        "consentSigned" to consentSigned,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class AnnouncementItem(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val targetAudience: String = "ALL",
    val channel: String = "IN_APP",
    val priority: String = "NORMAL",
    val senderName: String = "",
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "title" to title,
        "body" to body,
        "targetAudience" to targetAudience,
        "channel" to channel,
        "priority" to priority,
        "senderName" to senderName,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class LiveClassSession(
    val id: String = "",
    val title: String = "",
    val batchName: String = "",
    val teacherName: String = "",
    val meetingCode: String = "",
    val scheduledTimeIso: String = "",
    val durationMinutes: Int = 60,
    val status: String = "UPCOMING",
    val attendeeCount: Int = 0,
    val activePollQuestion: String = "",
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "title" to title,
        "batchName" to batchName,
        "teacherName" to teacherName,
        "meetingCode" to meetingCode,
        "scheduledTimeIso" to scheduledTimeIso,
        "durationMinutes" to durationMinutes,
        "status" to status,
        "attendeeCount" to attendeeCount,
        "activePollQuestion" to activePollQuestion,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class AiInteractionLog(
    val id: String = "",
    val userUid: String = "",
    val userRole: String = "",
    val featureType: String = "TUTOR_CHAT",
    val modelName: String = "gemini-3.8-flash",
    val promptText: String = "",
    val responseText: String = "",
    val tokensUsed: Int = 0,
    val safetyFlagged: Boolean = false,
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "userUid" to userUid,
        "userRole" to userRole,
        "featureType" to featureType,
        "modelName" to modelName,
        "promptText" to promptText,
        "responseText" to responseText,
        "tokensUsed" to tokensUsed,
        "safetyFlagged" to safetyFlagged,
        "createdAt" to createdAt
    )
}

data class InventoryAsset(
    val id: String = "",
    val assetCode: String = "",
    val name: String = "",
    val category: String = "LAB_PC",
    val locationLab: String = "Lab 1",
    val conditionStatus: String = "WORKING",
    val assignedTo: String = "",
    val purchaseCostPaise: Long = 0L,
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "assetCode" to assetCode,
        "name" to name,
        "category" to category,
        "locationLab" to locationLab,
        "conditionStatus" to conditionStatus,
        "assignedTo" to assignedTo,
        "purchaseCostPaise" to purchaseCostPaise,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class SupportTicket(
    val id: String = "",
    val ticketNo: String = "",
    val subject: String = "",
    val description: String = "",
    val category: String = "FEE_QUERY",
    val priority: String = "MEDIUM",
    val status: String = "OPEN",
    val requesterUid: String = "",
    val requesterName: String = "",
    val resolutionNotes: String = "",
    val npsScore: Int = 10,
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "ticketNo" to ticketNo,
        "subject" to subject,
        "description" to description,
        "category" to category,
        "priority" to priority,
        "status" to status,
        "requesterUid" to requesterUid,
        "requesterName" to requesterName,
        "resolutionNotes" to resolutionNotes,
        "npsScore" to npsScore,
        "createdAt" to createdAt
    )
}

data class SecurityAuditLog(
    val id: String = "",
    val actorUid: String = "",
    val actorEmail: String = "",
    val action: String = "",
    val module: String = "",
    val targetId: String = "",
    val severity: String = "INFO",
    val details: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "actorUid" to actorUid,
        "actorEmail" to actorEmail,
        "action" to action,
        "module" to module,
        "targetId" to targetId,
        "severity" to severity,
        "details" to details,
        "createdAt" to createdAt
    )
}

data class InstituteBackupOrConfig(
    val id: String = "",
    val title: String = "",
    val category: String = "BACKUP_SNAPSHOT",
    val payloadJson: String = "{}",
    val recordCount: Int = 0,
    val checksum: String = "",
    val ownerUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "title" to title,
        "category" to category,
        "payloadJson" to payloadJson,
        "recordCount" to recordCount,
        "checksum" to checksum,
        "ownerUid" to ownerUid,
        "createdAt" to createdAt
    )
}

data class FeesLedgerRecord(
    val id: String = "",
    val transactionId: String = "",
    val studentId: String = "",
    val studentName: String = "",
    val courseId: String = "",
    val amountPaise: Long = 0L,
    val paymentMode: String = "UPI",
    val transactionType: String = "FEE_PAYMENT",
    val receiptNo: String = "",
    val notes: String = "",
    val collectedByUid: String = "",
    val status: String = "COMPLETED",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "transactionId" to transactionId,
        "studentId" to studentId,
        "studentName" to studentName,
        "courseId" to courseId,
        "amountPaise" to amountPaise,
        "paymentMode" to paymentMode,
        "transactionType" to transactionType,
        "receiptNo" to receiptNo,
        "notes" to notes,
        "collectedByUid" to collectedByUid,
        "status" to status,
        "createdAt" to createdAt
    )
}

data class TeacherAttendanceRecord(
    val id: String = "",
    val teacherId: String = "",
    val teacherName: String = "",
    val dateIso: String = "",
    val checkInTimeIso: String = "",
    val checkOutTimeIso: String = "",
    val status: String = "PRESENT",
    val livenessScore: Float = 1.0f,
    val faceVectorHash: String = "",
    val deviceFingerprint: String = "",
    val isAutoAbsentCutoff: Boolean = false,
    val verifiedByCamera: Boolean = true,
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "teacherId" to teacherId,
        "teacherName" to teacherName,
        "dateIso" to dateIso,
        "checkInTimeIso" to checkInTimeIso,
        "checkOutTimeIso" to checkOutTimeIso,
        "status" to status,
        "livenessScore" to livenessScore,
        "faceVectorHash" to faceVectorHash,
        "deviceFingerprint" to deviceFingerprint,
        "isAutoAbsentCutoff" to isAutoAbsentCutoff,
        "verifiedByCamera" to verifiedByCamera,
        "createdAt" to createdAt
    )
}

data class StaffPayrollRecord(
    val id: String = "",
    val staffId: String = "",
    val staffName: String = "",
    val monthYear: String = "",
    val basicSalaryPaise: Long = 0L,
    val hraAllowancesPaise: Long = 0L,
    val specialAllowancesPaise: Long = 0L,
    val deductionsPaise: Long = 0L,
    val netPayablePaise: Long = 0L,
    val paymentStatus: String = "PAID",
    val paymentDateIso: String = "",
    val bankTxnReference: String = "",
    val approvedByUid: String = "",
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "staffId" to staffId,
        "staffName" to staffName,
        "monthYear" to monthYear,
        "basicSalaryPaise" to basicSalaryPaise,
        "hraAllowancesPaise" to hraAllowancesPaise,
        "specialAllowancesPaise" to specialAllowancesPaise,
        "deductionsPaise" to deductionsPaise,
        "netPayablePaise" to netPayablePaise,
        "paymentStatus" to paymentStatus,
        "paymentDateIso" to paymentDateIso,
        "bankTxnReference" to bankTxnReference,
        "approvedByUid" to approvedByUid,
        "createdAt" to createdAt
    )
}

data class ImmutableLedgerRecord(
    val id: String = "",
    val sequenceNumber: Long = 0L,
    val previousHash: String = "0000000000000000000000000000000000000000000000000000000000000000",
    val currentHash: String = "",
    val action: String = "",
    val module: String = "",
    val actorUid: String = "",
    val actorName: String = "",
    val payloadJson: String = "{}",
    val timestampEpochMs: Long = 0L,
    val createdAt: Timestamp = Timestamp.now()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "sequenceNumber" to sequenceNumber,
        "previousHash" to previousHash,
        "currentHash" to currentHash,
        "action" to action,
        "module" to module,
        "actorUid" to actorUid,
        "actorName" to actorName,
        "payloadJson" to payloadJson,
        "timestampEpochMs" to timestampEpochMs,
        "createdAt" to createdAt
    )
}

