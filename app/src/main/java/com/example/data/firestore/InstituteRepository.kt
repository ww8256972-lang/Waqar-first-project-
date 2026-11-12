package com.example.data.firestore

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class InstituteRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    companion object {
        const val BOOTSTRAPPED_ADMIN_EMAIL = "samdkhan8256972@gmail.com"

        fun create(context: Context): InstituteRepository {
            val databaseId = context.getString(R.string.firestore_database_id)
            val firestore = FirebaseFirestore.getInstance(databaseId)
            try {
                val currentSettings = firestore.firestoreSettings
                val cacheSettings = PersistentCacheSettings.newBuilder()
                    .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    .build()
                val updatedSettings = FirebaseFirestoreSettings.Builder(currentSettings)
                    .setLocalCacheSettings(cacheSettings)
                    .build()
                firestore.firestoreSettings = updatedSettings
            } catch (e: Exception) {
                Log.w("InstituteRepository", "Firestore persistence settings already initialized: ${e.message}")
            }
            return InstituteRepository(firestore, FirebaseAuth.getInstance())
        }

        fun isOfflineOrNetworkError(throwable: Throwable): Boolean {
            var curr: Throwable? = throwable
            while (curr != null) {
                if (curr is java.io.IOException) return true
                if (curr is FirebaseFirestoreException) {
                    if (curr.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                        curr.message?.contains("offline", ignoreCase = true) == true ||
                        curr.message?.contains("network", ignoreCase = true) == true ||
                        curr.message?.contains("deadline exceeded", ignoreCase = true) == true
                    ) {
                        return true
                    }
                }
                val msg = curr.message
                if (msg != null && (
                    msg.contains("offline", ignoreCase = true) ||
                    msg.contains("network", ignoreCase = true) ||
                    msg.contains("client is offline", ignoreCase = true) ||
                    msg.contains("unable to resolve host", ignoreCase = true) ||
                    msg.contains("connection refused", ignoreCase = true)
                )) {
                    return true
                }
                curr = curr.cause
            }
            return false
        }
    }

    // =========================================================================
    // M01: User Profile, Username Alias & Device Sessions
    // =========================================================================

    suspend fun saveUserProfile(profile: UserProfile) {
        val path = "users/${profile.uid}"
        try {
            db.collection("users").document(profile.uid).set(profile.toMap()).await()
        } catch (e: Exception) {
            if (!isOfflineOrNetworkError(e)) {
                handleFirestoreError(e, OperationType.WRITE, path, auth)
            }
        }
    }

    suspend fun getUserProfile(uid: String): UserProfile? {
        val path = "users/$uid"
        val docRef = db.collection("users").document(uid)
        return try {
            val snap = docRef.get(Source.DEFAULT).await()
            if (snap.exists()) snap.toObject(UserProfile::class.java) else null
        } catch (e: Exception) {
            if (isOfflineOrNetworkError(e)) {
                try {
                    val cachedSnap = docRef.get(Source.CACHE).await()
                    if (cachedSnap.exists()) cachedSnap.toObject(UserProfile::class.java) else null
                } catch (_: Exception) {
                    null
                }
            } else {
                handleFirestoreError(e, OperationType.GET, path, auth)
            }
        }
    }

    suspend fun reserveUsername(alias: UsernameAlias) {
        val cleanKey = alias.username.trim().lowercase()
        val path = "usernames/$cleanKey"
        try {
            db.collection("usernames").document(cleanKey)
                .set(alias.copy(username = cleanKey).toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun saveDeviceSession(session: DeviceSession) {
        val path = "sessions/${session.sessionId}"
        try {
            db.collection("sessions").document(session.sessionId).set(session.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun saveBoundPortalSession(
        uid: String,
        actorId: String,
        username: String,
        role: String,
        deviceFingerprint: String,
        expiresAt: com.google.firebase.Timestamp
    ) {
        val path = "sessions/$uid"
        val now = com.google.firebase.Timestamp(java.util.Date(System.currentTimeMillis() - 1000))
        val payload = mapOf(
            "sessionId" to uid,
            "uid" to uid,
            "userId" to uid,
            "actorId" to actorId.ifBlank { uid },
            "username" to username,
            "role" to role.lowercase(),
            "deviceName" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "deviceModel" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "platform" to "Android ${android.os.Build.VERSION.RELEASE}",
            "activeDeviceToken" to deviceFingerprint.ifBlank { uid },
            "active" to true,
            "expiresAt" to expiresAt,
            "createdAt" to now,
            "updatedAt" to now
        )
        try {
            db.collection("sessions").document(uid).set(payload).await()
        } catch (e: Exception) {
            if (!isOfflineOrNetworkError(e)) {
                handleFirestoreError(e, OperationType.WRITE, path, auth)
            }
        }
    }

    suspend fun revokeDeviceSession(session: DeviceSession) {
        val path = "sessions/${session.sessionId}"
        try {
            db.collection("sessions").document(session.sessionId)
                .set(session.copy(active = false).toMap()).await()
        } catch (e: Exception) {
            if (!isOfflineOrNetworkError(e)) {
                handleFirestoreError(e, OperationType.UPDATE, path, auth)
            }
        }
    }

    fun observeSessions(uid: String): Flow<List<DeviceSession>> = callbackFlow {
        val reg = db.collection("sessions")
            .whereEqualTo("uid", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { it.toObject(DeviceSession::class.java) }
                    ?.sortedByDescending { it.createdAt } ?: emptyList()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    suspend fun getPortalCredential(username: String): PortalCredential? {
        val cleanKey = username.trim().lowercase()
        val path = "portalCredentials/$cleanKey"
        val docRef = db.collection("portalCredentials").document(cleanKey)
        return try {
            val snap = docRef.get(Source.DEFAULT).await()
            if (snap.exists()) snap.toObject(PortalCredential::class.java) else null
        } catch (e: Exception) {
            if (isOfflineOrNetworkError(e)) {
                try {
                    val cachedSnap = docRef.get(Source.CACHE).await()
                    if (cachedSnap.exists()) cachedSnap.toObject(PortalCredential::class.java) else null
                } catch (_: Exception) {
                    null
                }
            } else {
                handleFirestoreError(e, OperationType.GET, path, auth)
            }
        }
    }

    suspend fun savePortalCredential(credential: PortalCredential) {
        val cleanKey = credential.username.trim().lowercase()
        val path = "portalCredentials/$cleanKey"
        try {
            db.collection("portalCredentials").document(cleanKey)
                .set(credential.copy(username = cleanKey).toMap()).await()
        } catch (e: Exception) {
            if (!isOfflineOrNetworkError(e)) {
                handleFirestoreError(e, OperationType.WRITE, path, auth)
            }
        }
    }

    suspend fun deletePortalCredential(username: String) {
        val cleanKey = username.trim().lowercase()
        val path = "portalCredentials/$cleanKey"
        try {
            db.collection("portalCredentials").document(cleanKey).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    fun observePortalCredentials(): Flow<List<PortalCredential>> =
        observeCollection("portalCredentials", PortalCredential::class.java)

    // =========================================================================
    // M03: Student Management
    // =========================================================================

    fun observeStudents(): Flow<List<StudentRecord>> = observeCollection("students", StudentRecord::class.java)

    suspend fun saveStudent(student: StudentRecord) {
        val path = "students/${student.id}"
        try {
            db.collection("students").document(student.id).set(student.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun deleteStudent(studentId: String) {
        val path = "students/$studentId"
        try {
            db.collection("students").document(studentId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    suspend fun findMatchingStudentForLogin(rawUsername: String): StudentRecord? {
        val clean = rawUsername.trim().lowercase()
        val slug = clean.replace(Regex("[^a-z0-9]"), "")
        return runCatching {
            val snap = try {
                db.collection("students").get(Source.DEFAULT).await()
            } catch (e: Exception) {
                if (isOfflineOrNetworkError(e)) {
                    db.collection("students").get(Source.CACHE).await()
                } else throw e
            }
            val all = snap.documents.mapNotNull { it.toObject(StudentRecord::class.java) }
            all.firstOrNull { s ->
                val stuNameSlug = s.fullName.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
                val guardSlug = s.guardianName.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
                val enrollLower = s.enrollmentNo.trim().lowercase()
                val emailUser = s.email.substringBefore("@").trim().lowercase()
                clean == stuNameSlug ||
                    clean == "${stuNameSlug}_wci" ||
                    slug == stuNameSlug ||
                    slug == "${stuNameSlug}wci" ||
                    clean == "parent_$guardSlug" ||
                    slug == "parent$guardSlug" ||
                    clean == enrollLower ||
                    slug == enrollLower.replace(Regex("[^a-z0-9]"), "") ||
                    clean == s.phone.trim() ||
                    (emailUser.isNotBlank() && clean == emailUser)
            }
        }.getOrNull()
    }

    suspend fun findMatchingStaffForLogin(rawUsername: String): StaffMember? {
        val clean = rawUsername.trim().lowercase()
        val slug = clean.replace(Regex("[^a-z0-9]"), "")
        return runCatching {
            val snap = try {
                db.collection("staff").get(Source.DEFAULT).await()
            } catch (e: Exception) {
                if (isOfflineOrNetworkError(e)) {
                    db.collection("staff").get(Source.CACHE).await()
                } else throw e
            }
            val all = snap.documents.mapNotNull { it.toObject(StaffMember::class.java) }
            all.firstOrNull { st ->
                val nameSlug = st.fullName.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
                val empLower = st.employeeCode.trim().lowercase()
                val emailUser = st.email.substringBefore("@").trim().lowercase()
                clean == nameSlug ||
                    clean == "${nameSlug}_wci" ||
                    slug == nameSlug ||
                    clean == empLower ||
                    clean == st.phone.trim() ||
                    (emailUser.isNotBlank() && clean == emailUser)
            }
        }.getOrNull()
    }

    // =========================================================================
    // M04: Admission CRM & Enquiries
    // =========================================================================

    fun observeEnquiries(): Flow<List<AdmissionEnquiry>> = observeCollection("enquiries", AdmissionEnquiry::class.java)

    suspend fun saveEnquiry(enquiry: AdmissionEnquiry) {
        val path = "enquiries/${enquiry.id}"
        try {
            db.collection("enquiries").document(enquiry.id).set(enquiry.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun deleteEnquiry(enquiryId: String) {
        val path = "enquiries/$enquiryId"
        try {
            db.collection("enquiries").document(enquiryId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    // =========================================================================
    // M05: Courses & Curriculum
    // =========================================================================

    fun observeCourses(): Flow<List<CourseRecord>> = observeCollection("courses", CourseRecord::class.java)

    suspend fun saveCourse(course: CourseRecord) {
        val path = "courses/${course.id}"
        try {
            db.collection("courses").document(course.id).set(course.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun deleteCourse(courseId: String) {
        val path = "courses/$courseId"
        try {
            db.collection("courses").document(courseId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    // =========================================================================
    // M06: Batches & Timetable
    // =========================================================================

    fun observeBatches(): Flow<List<BatchRecord>> = observeCollection("batches", BatchRecord::class.java)

    suspend fun saveBatch(batch: BatchRecord) {
        val path = "batches/${batch.id}"
        try {
            db.collection("batches").document(batch.id).set(batch.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun deleteBatch(batchId: String) {
        val path = "batches/$batchId"
        try {
            db.collection("batches").document(batchId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    // =========================================================================
    // M07: Attendance (Student & Staff + QR)
    // =========================================================================

    fun observeAttendance(): Flow<List<AttendanceRecord>> = observeCollection("attendance", AttendanceRecord::class.java)

    suspend fun saveAttendance(record: AttendanceRecord) {
        val path = "attendance/${record.id}"
        try {
            db.collection("attendance").document(record.id).set(record.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M08: Fees, Invoices & Payments
    // =========================================================================

    fun observeFees(): Flow<List<FeeInvoice>> = observeCollection("fees", FeeInvoice::class.java)

    suspend fun saveFeeInvoice(invoice: FeeInvoice) {
        val path = "fees/${invoice.id}"
        try {
            db.collection("fees").document(invoice.id).set(invoice.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun deleteFeeInvoice(feeId: String) {
        val path = "fees/$feeId"
        try {
            db.collection("fees").document(feeId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    // =========================================================================
    // M09: Expense, Vendor & Accounting Ledger
    // =========================================================================

    fun observeLedger(): Flow<List<LedgerEntry>> = observeCollection("ledger", LedgerEntry::class.java)

    suspend fun saveLedgerEntry(entry: LedgerEntry) {
        val path = "ledger/${entry.id}"
        try {
            db.collection("ledger").document(entry.id).set(entry.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun deleteLedgerEntry(entryId: String) {
        val path = "ledger/$entryId"
        try {
            db.collection("ledger").document(entryId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    // =========================================================================
    // M10: Exams, Question Bank & Mark Sheets
    // =========================================================================

    fun observeExams(): Flow<List<ExamRecord>> = observeCollection("exams", ExamRecord::class.java)
    fun observeExamResults(): Flow<List<ExamResult>> = observeCollection("examResults", ExamResult::class.java)

    suspend fun saveExam(exam: ExamRecord) {
        val path = "exams/${exam.id}"
        try {
            db.collection("exams").document(exam.id).set(exam.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun saveExamResult(result: ExamResult) {
        val path = "examResults/${result.id}"
        try {
            db.collection("examResults").document(result.id).set(result.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M11: Study Material, Assignments & Homework
    // =========================================================================

    fun observeMaterials(): Flow<List<StudyMaterial>> = observeCollection("materials", StudyMaterial::class.java)

    suspend fun saveMaterial(material: StudyMaterial) {
        val path = "materials/${material.id}"
        try {
            db.collection("materials").document(material.id).set(material.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun deleteMaterial(materialId: String) {
        val path = "materials/$materialId"
        try {
            db.collection("materials").document(materialId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    // =========================================================================
    // M12: Certificates, Verifiable QR & ID Cards
    // =========================================================================

    fun observeCertificates(): Flow<List<CertificateRecord>> = observeCollection("certificates", CertificateRecord::class.java)

    suspend fun saveCertificate(cert: CertificateRecord) {
        val path = "certificates/${cert.id}"
        try {
            db.collection("certificates").document(cert.id).set(cert.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M13 & M14: Staff Directory, HR & Payroll
    // =========================================================================

    fun observeStaff(): Flow<List<StaffMember>> = observeCollection("staff", StaffMember::class.java)
    fun observePayroll(): Flow<List<PayrollEntry>> = observeCollection("payroll", PayrollEntry::class.java)

    suspend fun saveStaff(member: StaffMember) {
        val path = "staff/${member.id}"
        try {
            db.collection("staff").document(member.id).set(member.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun savePayroll(entry: PayrollEntry) {
        val path = "payroll/${entry.id}"
        try {
            db.collection("payroll").document(entry.id).set(entry.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M15: Guardians & Parent Engagement
    // =========================================================================

    fun observeGuardians(): Flow<List<GuardianLink>> = observeCollection("guardians", GuardianLink::class.java)

    suspend fun saveGuardian(guardian: GuardianLink) {
        val path = "guardians/${guardian.id}"
        try {
            db.collection("guardians").document(guardian.id).set(guardian.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M16: Notifications, Broadcasts & Announcements
    // =========================================================================

    fun observeAnnouncements(): Flow<List<AnnouncementItem>> = observeCollection("announcements", AnnouncementItem::class.java)

    suspend fun saveAnnouncement(item: AnnouncementItem) {
        val path = "announcements/${item.id}"
        try {
            db.collection("announcements").document(item.id).set(item.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M17: Live Classes, Lab Bookings & Polls
    // =========================================================================

    fun observeLiveClasses(): Flow<List<LiveClassSession>> = observeCollection("liveClasses", LiveClassSession::class.java)

    suspend fun saveLiveClass(session: LiveClassSession) {
        val path = "liveClasses/${session.id}"
        try {
            db.collection("liveClasses").document(session.id).set(session.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M18: AI Tutor & Admin AI Assistant Logs
    // =========================================================================

    fun observeAiLogs(): Flow<List<AiInteractionLog>> = observeCollection("aiLogs", AiInteractionLog::class.java)

    suspend fun saveAiLog(log: AiInteractionLog) {
        val path = "aiLogs/${log.id}"
        try {
            db.collection("aiLogs").document(log.id).set(log.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M20: Inventory, Assets, Lab PCs & Library
    // =========================================================================

    fun observeAssets(): Flow<List<InventoryAsset>> = observeCollection("assets", InventoryAsset::class.java)

    suspend fun saveAsset(asset: InventoryAsset) {
        val path = "assets/${asset.id}"
        try {
            db.collection("assets").document(asset.id).set(asset.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M21: Helpdesk, Support Tickets & NPS Feedback
    // =========================================================================

    fun observeTickets(): Flow<List<SupportTicket>> = observeCollection("tickets", SupportTicket::class.java)

    suspend fun saveTicket(ticket: SupportTicket) {
        val path = "tickets/${ticket.id}"
        try {
            db.collection("tickets").document(ticket.id).set(ticket.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M22: Security Audit Log
    // =========================================================================

    fun observeAuditLogs(): Flow<List<SecurityAuditLog>> = observeCollection("auditLogs", SecurityAuditLog::class.java)

    suspend fun logAuditEvent(log: SecurityAuditLog) {
        val path = "auditLogs/${log.id}"
        try {
            db.collection("auditLogs").document(log.id).set(log.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path, auth)
        }
    }

    fun observeBackupsAndConfigs(): Flow<List<InstituteBackupOrConfig>> =
        observeCollection("backups", InstituteBackupOrConfig::class.java)

    suspend fun saveBackupOrConfig(record: InstituteBackupOrConfig) {
        val path = "backups/${record.id}"
        try {
            db.collection("backups").document(record.id).set(record.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // Enterprise Extensions: feesLedger, teacherAttendance, staffPayroll, immutableLedger
    // =========================================================================

    fun observeFeesLedger(): Flow<List<FeesLedgerRecord>> =
        observeCollection("feesLedger", FeesLedgerRecord::class.java)

    suspend fun saveFeesLedgerRecord(record: FeesLedgerRecord) {
        val path = "feesLedger/${record.id}"
        try {
            db.collection("feesLedger").document(record.id).set(record.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    fun observeTeacherAttendance(): Flow<List<TeacherAttendanceRecord>> =
        observeCollection("teacherAttendance", TeacherAttendanceRecord::class.java)

    suspend fun recordTeacherFaceBiometricAttendance(record: TeacherAttendanceRecord) {
        val path = "teacherAttendance/${record.id}"
        try {
            db.collection("teacherAttendance").document(record.id).set(record.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    fun observeStaffPayroll(): Flow<List<StaffPayrollRecord>> =
        observeCollection("staffPayroll", StaffPayrollRecord::class.java)

    suspend fun saveStaffPayroll(record: StaffPayrollRecord) {
        val path = "staffPayroll/${record.id}"
        try {
            db.collection("staffPayroll").document(record.id).set(record.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    fun observeImmutableLedger(): Flow<List<ImmutableLedgerRecord>> =
        observeCollection("immutableLedger", ImmutableLedgerRecord::class.java)

    suspend fun appendImmutableLedgerRecord(record: ImmutableLedgerRecord) {
        val path = "immutableLedger/${record.id}"
        try {
            db.collection("immutableLedger").document(record.id).set(record.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    private fun <T> observeCollection(collectionName: String, clazz: Class<T>): Flow<List<T>> = callbackFlow {
        val reg = db.collection(collectionName)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                val items = snap?.documents?.mapNotNull { it.toObject(clazz) } ?: emptyList()
                trySend(items)
            }
        awaitClose { reg.remove() }
    }
}
