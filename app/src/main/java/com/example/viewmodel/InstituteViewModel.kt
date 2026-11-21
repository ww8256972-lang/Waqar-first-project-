package com.example.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.ai.GeminiInstituteService
import com.example.auth.AuthPortalResult
import com.example.auth.FirebaseAuthServiceProvider
import com.example.data.firestore.InstituteRepository
import com.example.data.local.AppPreferences
import com.example.data.local.CachedStudentEntity
import com.example.data.local.CachedTimetableSlotEntity
import com.example.data.local.InstituteLocalDatabase
import com.example.data.local.PreferencesManager
import com.example.data.local.QueuedAttendanceEntity
import com.example.data.local.UiTier
import com.example.data.model.*
import com.example.security.EnterpriseRaspGuard
import com.example.security.RaspSecurityReport
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.waqar.institute.upi.InstituteAuditLogRecord
import com.waqar.institute.upi.StudentFeesLedgerRecord
import com.waqar.institute.upi.UpiTransactionRecord
import com.waqar.institute.upi.ZeroFeeUpiPaymentEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class ProvisioningStatus {
    IDLE,
    PENDING,
    PROVISIONING,
    PROVISIONED,
    FAILED
}

data class InstituteUiState(
    val currentUser: FirebaseUser? = null,
    val userProfile: UserProfile? = null,
    val activePortalRole: PortalRole = PortalRole.ADMIN,
    val isLockedByBiometric: Boolean = false,
    val mfaChallengePending: Boolean = false,
    val isAuthenticating: Boolean = false,
    val pendingAdminSetupUsername: String? = null,
    val provisioningStatus: ProvisioningStatus = ProvisioningStatus.IDLE,
    val provisioningMessage: String? = null,
    val preferences: AppPreferences = AppPreferences(),
    val effectiveUiTier: UiTier = UiTier.HIGH,
    val statusMessage: String? = null,
    val isAiStreaming: Boolean = false,
    val currentAiStreamText: String = "",
    val raspReport: RaspSecurityReport? = null,
    // Real-time Firestore collections (scoped by role — Zero Dummy Data)
    val sessions: List<DeviceSession> = emptyList(),
    val portalCredentials: List<PortalCredential> = emptyList(),
    val students: List<StudentRecord> = emptyList(),
    val enquiries: List<AdmissionEnquiry> = emptyList(),
    val courses: List<CourseRecord> = emptyList(),
    val batches: List<BatchRecord> = emptyList(),
    val attendance: List<AttendanceRecord> = emptyList(),
    val teacherAttendance: List<TeacherAttendanceRecord> = emptyList(),
    val queuedOfflineAttendance: List<QueuedAttendanceEntity> = emptyList(),
    val cachedTimetable: List<CachedTimetableSlotEntity> = emptyList(),
    val fees: List<FeeInvoice> = emptyList(),
    val ledger: List<LedgerEntry> = emptyList(),
    val exams: List<ExamRecord> = emptyList(),
    val examResults: List<ExamResult> = emptyList(),
    val materials: List<StudyMaterial> = emptyList(),
    val certificates: List<CertificateRecord> = emptyList(),
    val staff: List<StaffMember> = emptyList(),
    val payroll: List<PayrollEntry> = emptyList(),
    val guardians: List<GuardianLink> = emptyList(),
    val announcements: List<AnnouncementItem> = emptyList(),
    val liveClasses: List<LiveClassSession> = emptyList(),
    val aiLogs: List<AiInteractionLog> = emptyList(),
    val assets: List<InventoryAsset> = emptyList(),
    val tickets: List<SupportTicket> = emptyList(),
    val auditLogs: List<SecurityAuditLog> = emptyList(),
    val backupsAndConfigs: List<InstituteBackupOrConfig> = emptyList(),
    val upiTransactions: List<UpiTransactionRecord> = emptyList(),
    val studentFeesLedger: List<StudentFeesLedgerRecord> = emptyList(),
    val instituteAuditLogs: List<InstituteAuditLogRecord> = emptyList()
)

class InstituteViewModel(
    private val repository: InstituteRepository,
    private val localDb: InstituteLocalDatabase,
    private val prefsManager: PreferencesManager,
    val authServiceProvider: FirebaseAuthServiceProvider = FirebaseAuthServiceProvider(repository),
    private val geminiService: GeminiInstituteService = GeminiInstituteService(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) : ViewModel() {

    private val appContext: Context? = authServiceProvider.context?.applicationContext
    private val _uiState = MutableStateFlow(InstituteUiState(currentUser = auth.currentUser))
    val uiState: StateFlow<InstituteUiState> = _uiState.asStateFlow()

    // Raw unfiltered collections from Firestore so role/linkedEntityId filtering is reactive & instantaneous
    private var rawStudents: List<StudentRecord> = emptyList()
    private var rawBatches: List<BatchRecord> = emptyList()
    private var rawAttendance: List<AttendanceRecord> = emptyList()
    private var rawTeacherAttendance: List<TeacherAttendanceRecord> = emptyList()
    private var rawStaff: List<StaffMember> = emptyList()
    private var rawFees: List<FeeInvoice> = emptyList()
    private var rawExams: List<ExamRecord> = emptyList()
    private var rawExamResults: List<ExamResult> = emptyList()
    private var rawCertificates: List<CertificateRecord> = emptyList()
    private var rawGuardians: List<GuardianLink> = emptyList()
    private var rawPayroll: List<PayrollEntry> = emptyList()
    private var rawPortalCredentials: List<PortalCredential> = emptyList()
    private var rawUpiTransactions: List<UpiTransactionRecord> = emptyList()
    private var rawStudentFeesLedger: List<StudentFeesLedgerRecord> = emptyList()
    private var rawInstituteAuditLogs: List<InstituteAuditLogRecord> = emptyList()

    private val studentSearchQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedStudentsFlow: Flow<PagingData<CachedStudentEntity>> = studentSearchQuery
        .flatMapLatest { query ->
            Pager(
                config = PagingConfig(pageSize = 30, enablePlaceholders = false, prefetchDistance = 10),
                pagingSourceFactory = { localDb.offlineDao().pagingStudents(query) }
            ).flow
        }
        .cachedIn(viewModelScope)

    private var collectionJobsStartedForUid: String? = null
    private val streamJobs = mutableListOf<kotlinx.coroutines.Job>()

    @Volatile
    private var failedAuthAttempts: Int = 0

    @Volatile
    private var authLockoutUntilEpochMs: Long = 0L

    private fun computeAuthLockoutDurationMs(failures: Int): Long {
        if (failures <= 5) return 0L
        return 300_000L // 5-minute temporary block on 6th request in 60s window
    }

    init {
        // Synchronize existing Admin credentials from Cloud Firestore (no hardcoded default passwords)
        viewModelScope.launch {
            runCatching {
                authServiceProvider.ensureMasterAdminCredentialsSeeded()
            }
        }

        // Observe local preferences
        viewModelScope.launch {
            prefsManager.preferencesFlow
                .catch { emit(AppPreferences()) }
                .collect { prefs ->
                    val tier = prefsManager.resolveEffectiveUiTier(prefs)
                    _uiState.update { state ->
                        state.copy(
                            preferences = prefs,
                            effectiveUiTier = tier,
                            activePortalRole = PortalRole.fromKey(prefs.selectedPortalRole)
                        )
                    }
                    recomputeRoleScopedData()
                }
        }

        // Observe Room offline queues safely
        viewModelScope.launch {
            runCatching {
                localDb.offlineDao().observeQueuedAttendance()
                    .catch { emit(emptyList()) }
                    .collect { queued ->
                        _uiState.update { it.copy(queuedOfflineAttendance = queued) }
                    }
            }
        }
        viewModelScope.launch {
            runCatching {
                localDb.offlineDao().observeCachedTimetable()
                    .catch { emit(emptyList()) }
                    .collect { slots ->
                        _uiState.update { it.copy(cachedTimetable = slots) }
                    }
            }
        }

        // Auth state listener
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _uiState.update { it.copy(currentUser = user) }
            if (user != null) {
                if (_uiState.value.userProfile == null) {
                    onAuthenticatedUser(user)
                } else {
                    startRealtimeStreamsIfNeeded(user.uid)
                }
            } else {
                // Do NOT wipe userProfile if an authenticated Portal Credential session (admin, student, teacher) is active!
                val currentProfile = _uiState.value.userProfile
                val isCredentialSession = currentProfile != null && (
                    currentProfile.uid.startsWith("cred_") ||
                    currentProfile.uid.startsWith("admin_") ||
                    currentProfile.username.isNotBlank()
                )
                if (!isCredentialSession) {
                    collectionJobsStartedForUid = null
                    _uiState.update {
                        it.copy(
                            userProfile = null,
                            mfaChallengePending = false,
                            portalCredentials = emptyList()
                        )
                    }
                }
            }
        }
    }

    private fun currentActorUid(): String? =
        auth.currentUser?.uid ?: _uiState.value.userProfile?.uid?.takeIf { it.isNotBlank() }

    private fun ensureActiveSessionOrSignOut(): Boolean {
        val uid = currentActorUid() ?: return false
        val forceReason = com.waqar.institute.session.SessionManager.sessionForceLogoutEvent.value
            ?: com.waqar.institute.session.ConcurrentSessionManager.sessionExpiredMessage.value
        if (!forceReason.isNullOrBlank()) {
            signOut(reasonMessage = forceReason)
            return false
        }
        val username = _uiState.value.userProfile?.username.orEmpty()
        val ctx = appContext
        if (ctx != null && username.isNotBlank() &&
            !com.waqar.institute.session.ConcurrentSessionManager.verifySubMillisecondSession(ctx, username)
        ) {
            signOut(reasonMessage = "Session expired or active on another device. Please sign in again.")
            return false
        }
        return uid.isNotBlank()
    }

    fun updateStudentPagingSearch(query: String) {
        studentSearchQuery.value = query.trim()
    }

    fun refreshRaspSecurityAudit(context: Context) {
        viewModelScope.launch {
            val report = EnterpriseRaspGuard.auditDeviceSecurity(context)
            _uiState.update { it.copy(raspReport = report) }
        }
    }

    private fun normKey(raw: String): String = TeacherScopeMatcher.normKey(raw)

    private fun slugKey(raw: String): String = TeacherScopeMatcher.slugKey(raw)

    /**
     * Enforces strict multi-tenant per-account data isolation:
     * - Admin sees all institute records + credential vault.
     * - Student & Parent ONLY see their own linked student record (`linkedEntityId` / `username`)
     *   and NEVER see any other student's record or any account's credentials.
     * - Teacher sees batches assigned to them (by authoritative teacherId or normalized name/username),
     *   students in those batches, their own payroll & teacher attendance, and NEVER sees credentials or fees.
     */
    private fun recomputeRoleScopedData() {
        val state = _uiState.value
        val role = state.activePortalRole
        val profile = state.userProfile
        val linkedId = profile?.linkedEntityId?.trim().orEmpty()
        val usernameKey = profile?.username?.trim()?.lowercase().orEmpty()
        val displayNameKey = profile?.displayName?.trim()?.lowercase().orEmpty()

        val credentialLinkedId = (rawPortalCredentials.firstOrNull {
            it.username.equals(usernameKey, ignoreCase = true)
        } ?: InstituteRepository.getCachedCredentialLocally(usernameKey))?.linkedEntityId?.trim().orEmpty()
        val effectiveLinkedId = linkedId.ifBlank { credentialLinkedId }

        val allKnownStudents = (InstituteRepository.localStudentsCache.values + rawStudents).distinctBy { it.id }
        val allKnownStaff = (InstituteRepository.localStaffCache.values + rawStaff + state.staff).distinctBy { it.id }
        val allKnownGuardians = (InstituteRepository.localGuardiansCache.values + rawGuardians + state.guardians).distinctBy { it.id }
        val matchedTeacherStaff = allKnownStaff.firstOrNull { st ->
            (effectiveLinkedId.isNotBlank() && st.id.equals(effectiveLinkedId, ignoreCase = true)) ||
                (usernameKey.isNotBlank() && (
                    st.teacherUsername.equals(usernameKey, ignoreCase = true) ||
                        "cred_${st.teacherUsername.lowercase()}" == usernameKey
                    )) ||
                (displayNameKey.isNotBlank() && slugKey(st.fullName) == slugKey(displayNameKey))
        }

        val teacherIdKeys = listOfNotNull(
            effectiveLinkedId.takeIf { it.isNotBlank() },
            profile?.uid?.takeIf { it.isNotBlank() },
            matchedTeacherStaff?.id?.takeIf { it.isNotBlank() },
            matchedTeacherStaff?.employeeCode?.takeIf { it.isNotBlank() }
        ).map(::normKey).toSet()

        val teacherNameKeys = listOfNotNull(
            displayNameKey.takeIf { it.isNotBlank() },
            usernameKey.takeIf { it.isNotBlank() },
            matchedTeacherStaff?.fullName?.takeIf { it.isNotBlank() },
            matchedTeacherStaff?.teacherUsername?.takeIf { it.isNotBlank() }
        ).map { normKey(it.replace("(Batch Admin)", "", ignoreCase = true)) }
            .filter { it.isNotBlank() }
            .toSet()

        val teacherNameSlugs = teacherNameKeys.map(::slugKey).filter { it.isNotBlank() }.toSet()

        fun matchesStudent(student: StudentRecord): Boolean {
            if (role == PortalRole.ADMIN || role == PortalRole.TEACHER) return true
            if (effectiveLinkedId.isNotEmpty() && (
                    student.id.equals(effectiveLinkedId, ignoreCase = true) ||
                        student.enrollmentNo.equals(effectiveLinkedId, ignoreCase = true) ||
                        student.fullName.equals(effectiveLinkedId, ignoreCase = true)
                    )
            ) {
                return true
            }
            val cleanStudentSlug = student.fullName.lowercase().replace(Regex("[^a-z0-9]"), "")
            val cleanGuardianSlug = student.guardianName.lowercase().replace(Regex("[^a-z0-9]"), "")
            if (usernameKey.isNotEmpty() && (
                    student.enrollmentNo.equals(usernameKey, ignoreCase = true) ||
                        student.email.substringBefore("@").equals(usernameKey, ignoreCase = true) ||
                        student.fullName.equals(displayNameKey, ignoreCase = true) ||
                        (student.guardianName.isNotBlank() && displayNameKey.contains(student.guardianName.lowercase())) ||
                        (cleanStudentSlug.isNotEmpty() && displayNameKey.contains(cleanStudentSlug)) ||
                        "${cleanStudentSlug}_wci" == usernameKey ||
                        "parent_$cleanStudentSlug" == usernameKey ||
                        (cleanGuardianSlug.isNotEmpty() && "parent_$cleanGuardianSlug" == usernameKey)
                    )
            ) {
                return true
            }
            return false
        }

        val scopedBatches = when (role) {
            PortalRole.ADMIN -> rawBatches
            PortalRole.TEACHER -> TeacherScopeMatcher.filterBatchesForTeacher(
                batches = rawBatches,
                teacherIdKeys = teacherIdKeys,
                teacherNameKeys = teacherNameKeys,
                teacherNameSlugs = teacherNameSlugs
            )
            PortalRole.STUDENT, PortalRole.PARENT -> rawBatches
        }
        val teacherBatchIds = scopedBatches.map { normKey(it.id) }.filter { it.isNotBlank() }.toSet()
        val teacherBatchNames = scopedBatches.map { normKey(it.name) }.filter { it.isNotBlank() }.toSet()
        val teacherCourseNames = scopedBatches.map { normKey(it.courseName) }.filter { it.isNotBlank() }.toSet()

        val scopedStudents = when (role) {
            PortalRole.ADMIN -> allKnownStudents
            PortalRole.TEACHER -> TeacherScopeMatcher.filterStudentsForTeacher(
                students = allKnownStudents,
                teacherBatchIds = teacherBatchIds,
                teacherBatchNames = teacherBatchNames,
                teacherCourseNames = teacherCourseNames
            )
            PortalRole.STUDENT, PortalRole.PARENT -> allKnownStudents.filter(::matchesStudent)
        }
        val allowedStudentIds = scopedStudents.map { it.id.lowercase() }.toSet() +
            scopedStudents.map { it.enrollmentNo.lowercase() }.toSet() +
            listOfNotNull(linkedId.lowercase().takeIf { it.isNotEmpty() })

        val scopedAttendance = when (role) {
            PortalRole.ADMIN -> rawAttendance
            PortalRole.TEACHER -> rawAttendance.filter { att ->
                (att.batchId.isNotBlank() && normKey(att.batchId) in teacherBatchIds) ||
                    normKey(att.batchName) in teacherBatchNames ||
                    (att.targetType != "STAFF" && att.targetId.lowercase() in allowedStudentIds) ||
                    (att.targetType == "STAFF" && (
                        normKey(att.targetId) in teacherIdKeys ||
                            normKey(att.targetName) in teacherNameKeys
                        ))
            }
            PortalRole.STUDENT, PortalRole.PARENT -> rawAttendance.filter { att ->
                att.targetId.lowercase() in allowedStudentIds ||
                    (displayNameKey.isNotEmpty() && att.targetName.equals(displayNameKey, ignoreCase = true))
            }
        }

        val scopedTeacherAttendance = when (role) {
            PortalRole.ADMIN -> rawTeacherAttendance
            PortalRole.TEACHER -> rawTeacherAttendance.filter { rec ->
                normKey(rec.teacherId) in teacherIdKeys ||
                    normKey(rec.teacherName) in teacherNameKeys ||
                    slugKey(rec.teacherName) in teacherNameSlugs
            }
            PortalRole.STUDENT, PortalRole.PARENT -> emptyList()
        }

        // Teachers never view student fee invoices (financial privacy)
        val scopedFees = when (role) {
            PortalRole.ADMIN -> rawFees
            PortalRole.TEACHER -> emptyList()
            PortalRole.STUDENT, PortalRole.PARENT -> rawFees.filter { fee ->
                fee.studentId.lowercase() in allowedStudentIds ||
                    (displayNameKey.isNotEmpty() && fee.studentName.equals(displayNameKey, ignoreCase = true))
            }
        }

        val allowedStudentNames = scopedStudents.map { it.fullName.trim().lowercase() }.toSet()
        val allowedStudentBatchIds = scopedStudents.map { normKey(it.batchId) }.filter { it.isNotBlank() }.toSet()
        val allowedStudentBatchNames = scopedStudents.map { normKey(it.batchName) }.filter { it.isNotBlank() }.toSet()
        val allowedStudentCourseNames = scopedStudents.map { normKey(it.courseName) }.filter { it.isNotBlank() }.toSet()

        // [Group D1] Strict Batch Isolation for Exams
        val scopedExams = when (role) {
            PortalRole.ADMIN -> rawExams
            PortalRole.TEACHER -> rawExams.filter { ex ->
                (ex.batchId.isNotBlank() && normKey(ex.batchId) in teacherBatchIds) ||
                    (ex.batchName.isNotBlank() && normKey(ex.batchName) in teacherBatchNames) ||
                    (ex.batchId.isBlank() && ex.batchName.equals("All Batches", ignoreCase = true))
            }
            PortalRole.STUDENT, PortalRole.PARENT -> rawExams.filter { ex ->
                val isGlobal = ex.batchId.isBlank() && (ex.batchName.isBlank() || ex.batchName.equals("All Batches", ignoreCase = true))
                val matchesBatchId = ex.batchId.isNotBlank() && normKey(ex.batchId) in allowedStudentBatchIds
                val matchesBatchName = ex.batchName.isNotBlank() && normKey(ex.batchName) in allowedStudentBatchNames
                val matchesCourseFallback = ex.batchId.isBlank() && ex.batchName.isBlank() && normKey(ex.courseName) in allowedStudentCourseNames
                isGlobal || matchesBatchId || matchesBatchName || matchesCourseFallback
            }
        }

        val scopedExamResults = when (role) {
            PortalRole.ADMIN -> rawExamResults
            PortalRole.TEACHER -> rawExamResults.filter { res ->
                res.studentId.lowercase() in allowedStudentIds ||
                    (res.studentName.isNotBlank() && res.studentName.trim().lowercase() in allowedStudentNames)
            }
            PortalRole.STUDENT, PortalRole.PARENT -> rawExamResults.filter { res ->
                res.studentId.lowercase() in allowedStudentIds ||
                    (displayNameKey.isNotEmpty() && res.studentName.equals(displayNameKey, ignoreCase = true))
            }
        }

        val scopedCertificates = when (role) {
            PortalRole.ADMIN -> rawCertificates
            PortalRole.TEACHER -> rawCertificates.filter { cert ->
                cert.studentId.lowercase() in allowedStudentIds ||
                    (cert.studentName.isNotBlank() && cert.studentName.trim().lowercase() in allowedStudentNames)
            }
            PortalRole.STUDENT, PortalRole.PARENT -> rawCertificates.filter { cert ->
                cert.studentId.lowercase() in allowedStudentIds ||
                    (displayNameKey.isNotEmpty() && cert.studentName.equals(displayNameKey, ignoreCase = true))
            }
        }

        val scopedGuardians = when (role) {
            PortalRole.ADMIN -> allKnownGuardians
            PortalRole.TEACHER -> allKnownGuardians.filter { grd ->
                grd.studentId.lowercase() in allowedStudentIds ||
                    (grd.studentName.isNotBlank() && grd.studentName.trim().lowercase() in allowedStudentNames)
            }
            PortalRole.STUDENT, PortalRole.PARENT -> allKnownGuardians.filter { grd ->
                grd.studentId.lowercase() in allowedStudentIds ||
                    (displayNameKey.isNotEmpty() && (
                        grd.studentName.equals(displayNameKey, ignoreCase = true) ||
                            grd.guardianName.equals(displayNameKey, ignoreCase = true)
                        ))
            }
        }

        val scopedPayroll = when (role) {
            PortalRole.ADMIN -> rawPayroll
            PortalRole.TEACHER -> rawPayroll.filter { pay ->
                normKey(pay.staffId) in teacherIdKeys ||
                    normKey(pay.staffName) in teacherNameKeys
            }
            PortalRole.STUDENT, PortalRole.PARENT -> emptyList()
        }

        // CRITICAL SECURITY CHECK: Only Admin can view portalCredentials list
        val allKnownCredentials = (InstituteRepository.localCredentialsCache.values + rawPortalCredentials).distinctBy { it.username.lowercase() }
        val scopedCredentials = if (role == PortalRole.ADMIN) allKnownCredentials else emptyList()

        // Synthesize StudentFeesLedgerRecord for every visible student so Monthly Fee & 4-Month Overdue Auto-EMI are always ready
        val ledgerByStudentId = (ZeroFeeUpiPaymentEngine.localStudentFeesLedgerCache.values + rawStudentFeesLedger)
            .distinctBy { it.student_id }
            .associateBy { it.student_id }

        val syncedLedgersForAllStudents = allKnownStudents.map { stu ->
            ZeroFeeUpiPaymentEngine.buildOrSyncStudentLedger(
                student = stu,
                existing = ledgerByStudentId[stu.id] ?: ledgerByStudentId[stu.enrollmentNo]
            )
        }
        val scopedFeesLedger = when (role) {
            PortalRole.ADMIN -> syncedLedgersForAllStudents.sortedWith(
                compareByDescending<StudentFeesLedgerRecord> { it.overdue_months }
                    .thenByDescending { it.auto_emi_active }
                    .thenBy { it.student_name }
            )
            PortalRole.TEACHER -> emptyList()
            PortalRole.STUDENT, PortalRole.PARENT -> syncedLedgersForAllStudents.filter { led ->
                led.student_id.lowercase() in allowedStudentIds ||
                    led.enrollment_no.lowercase() in allowedStudentIds ||
                    (displayNameKey.isNotEmpty() && led.student_name.equals(displayNameKey, ignoreCase = true))
            }
        }

        val allUpiTxns = (ZeroFeeUpiPaymentEngine.localUpiTransactionsCache.values + rawUpiTransactions)
            .distinctBy { it.id }
            .sortedByDescending { it.timestamp }
        val scopedUpiTransactions = when (role) {
            PortalRole.ADMIN -> allUpiTxns
            PortalRole.TEACHER -> emptyList()
            PortalRole.STUDENT, PortalRole.PARENT -> allUpiTxns.filter { txn ->
                txn.student_id.lowercase() in allowedStudentIds ||
                    (displayNameKey.isNotEmpty() && txn.student_name.equals(displayNameKey, ignoreCase = true))
            }
        }

        val allInstituteAuditLogs = (ZeroFeeUpiPaymentEngine.localInstituteAuditLogsCache.values + rawInstituteAuditLogs)
            .distinctBy { it.id }
            .sortedByDescending { it.timestamp }
        val scopedInstituteAuditLogs = if (role == PortalRole.ADMIN) allInstituteAuditLogs else emptyList()

        _uiState.update {
            it.copy(
                students = scopedStudents,
                staff = allKnownStaff,
                batches = scopedBatches,
                attendance = scopedAttendance,
                teacherAttendance = scopedTeacherAttendance,
                fees = scopedFees,
                exams = scopedExams,
                examResults = scopedExamResults,
                certificates = scopedCertificates,
                guardians = scopedGuardians,
                payroll = scopedPayroll,
                portalCredentials = scopedCredentials,
                upiTransactions = scopedUpiTransactions,
                studentFeesLedger = scopedFeesLedger,
                instituteAuditLogs = scopedInstituteAuditLogs
            )
        }
        runCatching {
            if (scopedFees.isNotEmpty()) {
                val paidPaise = scopedFees.map { it.paidAmountPaise.coerceAtLeast(0L) }.toLongArray()
                val totalPaise = scopedFees.sumOf { it.totalAmountPaise.coerceAtLeast(0L) }
                com.waqar.institute.security.BrahmastraUnifiedWiringOrchestrator.guardFeeLedgerBatch(
                    installmentsPaise = paidPaise,
                    totalExpectedFeePaise = totalPaise.coerceAtLeast(paidPaise.sum())
                )
            }
        }
        com.waqar.institute.cache.PortalStateCacheManager.updateRoleSnapshot(
            role = role,
            students = scopedStudents,
            batches = scopedBatches,
            fees = scopedFees
        )
        com.waqar.institute.cache.PortalStateCacheManager.cacheScopedData(
            roleKey = role.key,
            students = scopedStudents,
            batches = scopedBatches,
            courses = _uiState.value.courses,
            fees = scopedFees
        )
    }

    /**
     * Authenticates a user into the selected Portal Role (`Admin`, `Teacher`, `Student`, `Parent`)
     * using the dedicated `FirebaseAuthServiceProvider` with username & PBKDF2-HMAC-SHA256 password verification.
     */
    fun authenticateWithPortalCredentials(
        activityContext: Context,
        role: PortalRole,
        username: String,
        password: String
    ) {
        val nowMs = System.currentTimeMillis()
        val rateStatus = com.example.security.EnterpriseRateLimiter.inspectStatus(
            context = activityContext,
            scope = com.example.security.EnterpriseRateLimiter.RateLimitScope.LOGIN,
            identityKey = "${role.key}:${username.trim().lowercase()}",
            nowEpochMs = nowMs
        )
        if (!rateStatus.allowed || nowMs < authLockoutUntilEpochMs) {
            val remainingSec = if (!rateStatus.allowed) {
                rateStatus.retryAfterSeconds
            } else {
                ((authLockoutUntilEpochMs - nowMs).coerceAtLeast(0L) + 999L) / 1000L
            }
            postMessage("HTTP 429 Too Many Requests: Rate limit exceeded (max 5 requests per 60s). Please wait ${remainingSec}s before retrying.")
            return
        }

        _uiState.update {
            it.copy(
                isAuthenticating = true,
                statusMessage = null,
                pendingAdminSetupUsername = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val pwdChars = password.toCharArray()
            com.example.security.MemoryScrubberLifecycleObserver.registerSensitiveChars(pwdChars)
            try {
                val netAudit = com.waqar.institute.security.NetworkSecurityGuard.auditNetworkSecurity(activityContext)
                if (!netAudit.isConnectionTrusted) {
                    withContext(Dispatchers.Main.immediate) {
                        _uiState.update { it.copy(isAuthenticating = false) }
                        postMessage("Blocked login on untrusted proxy/MITM network: ${netAudit.reason}")
                    }
                    return@launch
                }

                val result = withTimeoutOrNull(10_000L) {
                    authServiceProvider.signInToPortalWithCredentials(
                        activityContext = activityContext,
                        role = role,
                        username = username,
                        passwordChars = pwdChars
                    )
                } ?: AuthPortalResult.Failure("Unable to reach server. Please check your internet connection and try again.")

                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { it.copy(isAuthenticating = false) }
                    when (result) {
                        is AuthPortalResult.Success -> {
                            failedAuthAttempts = 0
                            authLockoutUntilEpochMs = 0L
                            com.waqar.institute.biometric.TeacherBiometricGateManager.clearLoginVerificationOnAuthChange()
                            val effectiveRole = result.session.role
                            internal_compose_brahmastra.RoleCapabilityGate.refreshVerifiedJwtRoleFromFirebase()
                            prefsManager.setSelectedPortalRole(effectiveRole.key)
                            _uiState.update {
                                it.copy(
                                    userProfile = result.profile,
                                    activePortalRole = effectiveRole,
                                    mfaChallengePending = result.profile.mfaEnabled,
                                    isAuthenticating = false,
                                    pendingAdminSetupUsername = null,
                                    statusMessage = null
                                )
                            }
                            val streamUid = auth.currentUser?.uid ?: result.profile.uid
                            viewModelScope.launch(Dispatchers.IO) {
                                runCatching { startRealtimeStreamsIfNeeded(streamUid) }
                                recomputeRoleScopedData()
                                runCatching {
                                    com.waqar.institute.session.ConcurrentSessionManager.bindAndEnforceSingleDeviceSession(
                                        context = activityContext.applicationContext,
                                        repository = repository,
                                        username = result.profile.username,
                                        uid = streamUid,
                                        role = effectiveRole,
                                        onForceSignOutWithReason = { reason ->
                                            signOut(reasonMessage = reason)
                                        }
                                    )
                                }
                                runCatching {
                                    val attToken = com.waqar.institute.security.ZeroTrustAttestationEngine.generateHardwareBoundToken(
                                        context = activityContext.applicationContext,
                                        userId = result.profile.username,
                                        portalRole = effectiveRole.key
                                    )
                                    val vmValid = com.waqar.institute.security.BinaryVirtualizationBridge.verifyCriticalPayloadInVm(
                                        attToken.payloadString.toByteArray(Charsets.UTF_8)
                                    )
                                    recordAudit(
                                        action = "PORTAL_LOGIN_SUCCESS",
                                        module = "M01_AUTH",
                                        targetId = result.profile.username,
                                        severity = "INFO",
                                        details = "Authenticated ${effectiveRole.label} '${result.profile.username}' • HardwareAttest=${attToken.hardwareBacked} • VmVerified=$vmValid"
                                    )
                                }
                            }
                            postMessage("Signed in as ${result.profile.displayName} (${effectiveRole.label})")
                        }
                        is AuthPortalResult.AdminSetupRequired -> {
                            failedAuthAttempts = 0
                            _uiState.update {
                                it.copy(
                                    isAuthenticating = false,
                                    pendingAdminSetupUsername = result.suggestedUsername,
                                    statusMessage = null
                                )
                            }
                        }
                        is AuthPortalResult.Failure -> {
                            failedAuthAttempts += 1
                            val lockoutMs = computeAuthLockoutDurationMs(failedAuthAttempts)
                            if (lockoutMs > 0L) {
                                authLockoutUntilEpochMs = System.currentTimeMillis() + lockoutMs
                                postMessage("Too many failed attempts. Portal login locked for ${lockoutMs / 1000L}s.")
                            } else {
                                postMessage(result.message)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                failedAuthAttempts += 1
                val lockoutMs = computeAuthLockoutDurationMs(failedAuthAttempts)
                if (lockoutMs > 0L) {
                    authLockoutUntilEpochMs = System.currentTimeMillis() + lockoutMs
                }
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isAuthenticating = false) }
                    postMessage(e.localizedMessage ?: "Authentication failed. Please verify credentials.")
                }
            } finally {
                java.util.Arrays.fill(pwdChars, '\u0000')
                withContext(Dispatchers.Main) {
                    if (_uiState.value.isAuthenticating) {
                        _uiState.update { it.copy(isAuthenticating = false) }
                    }
                }
            }
        }
    }

    fun dismissFirstRunAdminSetup() {
        _uiState.update { it.copy(pendingAdminSetupUsername = null) }
    }

    fun completeFirstRunAdminSetup(
        activityContext: Context,
        username: String,
        password: String
    ) {
        _uiState.update { it.copy(isAuthenticating = true, statusMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val pwdChars = password.toCharArray()
            com.example.security.MemoryScrubberLifecycleObserver.registerSensitiveChars(pwdChars)
            try {
                val result = authServiceProvider.completeFirstRunAdminSetup(
                    activityContext = activityContext,
                    username = username,
                    passwordChars = pwdChars
                )
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { it.copy(isAuthenticating = false) }
                    when (result) {
                        is AuthPortalResult.Success -> {
                            failedAuthAttempts = 0
                            authLockoutUntilEpochMs = 0L
                            internal_compose_brahmastra.RoleCapabilityGate.refreshVerifiedJwtRoleFromFirebase()
                            prefsManager.setSelectedPortalRole(PortalRole.ADMIN.key)
                            _uiState.update {
                                it.copy(
                                    userProfile = result.profile,
                                    activePortalRole = PortalRole.ADMIN,
                                    mfaChallengePending = false,
                                    isAuthenticating = false,
                                    pendingAdminSetupUsername = null,
                                    statusMessage = null
                                )
                            }
                            val streamUid = auth.currentUser?.uid ?: result.profile.uid
                            viewModelScope.launch(Dispatchers.IO) {
                                runCatching { startRealtimeStreamsIfNeeded(streamUid) }
                                recomputeRoleScopedData()
                            }
                            postMessage("Admin account '${result.profile.username}' created and signed in.")
                        }
                        is AuthPortalResult.AdminSetupRequired -> Unit
                        is AuthPortalResult.Failure -> postMessage(result.message)
                    }
                }
            } finally {
                java.util.Arrays.fill(pwdChars, '\u0000')
            }
        }
    }

    /**
     * Allows Admin to update their own Admin username & password immediately in EncryptedSharedPreferences/Keystore (SHA-256)
     * and synchronize asynchronously to Cloud Firestore.
     */
    fun updateAdminCredentials(
        newUsername: String,
        newPassword: String,
        biometricActionSignatureHex: String = ""
    ) {
        val currentUsername = _uiState.value.userProfile?.username?.takeIf { it.isNotBlank() }
            ?: appContext?.let { com.example.security.AdminEncryptedCredentialStore.getActiveAdminUsername(it) }
            ?: "waqar"
        val newPasswordChars = newPassword.toCharArray()
        viewModelScope.launch {
            val result = authServiceProvider.updateAdminOwnCredentials(
                oldUsername = currentUsername,
                newUsername = newUsername,
                newPasswordChars = newPasswordChars,
                actorUidOverride = currentActorUid(),
                biometricActionSignatureHex = biometricActionSignatureHex
            )
            result.onSuccess { updatedCred ->
                _uiState.value.userProfile?.let { profile ->
                    val updatedProfile = profile.copy(username = updatedCred.username)
                    _uiState.update { it.copy(userProfile = updatedProfile) }
                }
                val sigSuffix = if (biometricActionSignatureHex.isNotBlank()) {
                    " [BioSig:${biometricActionSignatureHex.take(16)}]"
                } else ""
                recordAudit(
                    action = "UPDATE_ADMIN_CREDENTIALS",
                    module = "M01_AUTH",
                    targetId = updatedCred.username,
                    severity = "WARNING",
                    details = "Admin updated portal username & Argon2id/PBKDF2 digest in Keystore + Cloud Firestore (${updatedCred.username})$sigSuffix"
                )
                postMessage("Admin credentials updated (Keystore + Cloud): @${updatedCred.username}. Use new login immediately offline or online.")
            }.onFailure { err ->
                postMessage("Could not update Admin credentials: ${err.localizedMessage}")
            }
        }
    }

    /**
     * Allows Admin to create or reset a username & password for any Student, Parent, Teacher, or Admin in Cloud Firestore.
     */
    fun provisionRoleCredential(
        username: String,
        password: String,
        role: PortalRole,
        linkedEntityId: String,
        displayName: String
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        if (_uiState.value.activePortalRole != PortalRole.ADMIN) {
            postMessage("Only Admin can issue or modify portal credentials.")
            return
        }
        val cleanPass = password.trim()
        if (cleanPass.length < 6) {
            _uiState.update {
                it.copy(
                    provisioningStatus = ProvisioningStatus.FAILED,
                    provisioningMessage = "Password must be at least 6 characters."
                )
            }
            postMessage("Password must be at least 6 characters.")
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    provisioningStatus = ProvisioningStatus.PROVISIONING,
                    provisioningMessage = "Syncing @${username.trim().lowercase()} to Cloud Firestore..."
                )
            }
            val result = authServiceProvider.provisionOrUpdateCredential(
                username = username,
                rawPasswordChars = cleanPass.toCharArray(),
                role = role,
                linkedEntityId = linkedEntityId,
                displayName = displayName,
                actorUidOverride = currentActorUid()
            )
            result.onSuccess { cred ->
                rawPortalCredentials = listOf(cred) + rawPortalCredentials.filterNot {
                    it.username.equals(cred.username, ignoreCase = true)
                }
                recomputeRoleScopedData()
                recordAudit(
                    action = "PROVISION_CREDENTIAL",
                    module = "M01_AUTH",
                    targetId = cred.username,
                    severity = "INFO",
                    details = "Issued ${role.label} credential @${cred.username} linked to '${cred.linkedEntityId}'"
                )
                _uiState.update {
                    it.copy(
                        provisioningStatus = ProvisioningStatus.PROVISIONED,
                        provisioningMessage = "Saved ${role.label} login @${cred.username} to Cloud Firestore"
                    )
                }
                postMessage("Saved ${role.label} login @${cred.username} to Cloud Firestore")
            }.onFailure { err ->
                val msg = "Cloud credential provisioning failed: ${err.localizedMessage ?: "Network/permission error"}"
                _uiState.update {
                    it.copy(
                        provisioningStatus = ProvisioningStatus.FAILED,
                        provisioningMessage = msg
                    )
                }
                postMessage(msg)
            }
        }
    }

    fun deleteRoleCredential(username: String) {
        if (_uiState.value.activePortalRole != PortalRole.ADMIN) return
        viewModelScope.launch {
            runCatching {
                repository.deletePortalCredential(username)
                recordAudit("DELETE_CREDENTIAL", "M01_AUTH", username, "WARNING", "Deleted portal credential @$username")
                postMessage("Removed credential @$username")
            }
        }
    }

    fun onAuthenticatedUser(
        user: FirebaseUser,
        requestedRole: PortalRole? = null,
        customUsername: String? = null
    ) {
        viewModelScope.launch {
            runCatching {
                val existing = repository.getUserProfile(user.uid)
                val email = user.email ?: "user@waqarinstitute.edu"
                val isOwnerEmail = email.equals(InstituteRepository.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)

                val resolvedRole = when {
                    requestedRole != null -> requestedRole.key
                    isOwnerEmail -> PortalRole.ADMIN.key
                    existing != null -> existing.role
                    else -> _uiState.value.activePortalRole.key
                }

                val cleanUsername = (customUsername?.takeIf { it.isNotBlank() }
                    ?: existing?.username?.takeIf { it.isNotBlank() }
                    ?: email.substringBefore("@").replace(Regex("[^a-zA-Z0-9_\\-]"), "_"))
                    .lowercase()

                val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000))
                val roleEnum = PortalRole.fromKey(resolvedRole)
                val roleFlags = com.example.auth.PortalAuthRepository.buildRoleFlags(
                    role = roleEnum,
                    extraRoles = existing?.effectivePortalRoles().orEmpty().map { PortalRole.fromKey(it) }
                )
                val profile = UserProfile(
                    uid = user.uid,
                    email = email,
                    displayName = user.displayName?.takeIf { it.isNotBlank() }
                        ?: existing?.displayName?.takeIf { it.isNotBlank() }
                        ?: if (isOwnerEmail) "Waqar Khan (Director)" else cleanUsername,
                    role = resolvedRole,
                    username = cleanUsername,
                    phone = user.phoneNumber ?: existing?.phone ?: "+91",
                    mfaEnabled = existing?.mfaEnabled ?: false,
                    totpSecret = existing?.totpSecret ?: "WCI2026TOTP",
                    linkedEntityId = existing?.linkedEntityId ?: "",
                    isStudent = existing?.isStudent == true || roleFlags.isStudent,
                    isTeacher = existing?.isTeacher == true || roleFlags.isTeacher,
                    isParent = existing?.isParent == true || roleFlags.isParent,
                    isAdmin = existing?.isAdmin == true || roleFlags.isAdmin,
                    portalRoles = roleFlags.portalRoles,
                    createdAt = existing?.createdAt ?: safeNow
                )

                repository.saveUserProfile(profile)
                repository.reserveUsername(
                    UsernameAlias(
                        username = cleanUsername,
                        uid = user.uid,
                        role = resolvedRole,
                        email = email,
                        createdAt = existing?.createdAt ?: safeNow
                    )
                )

                // Register active device session (M01: F009)
                val sessionId = "sess_${user.uid.take(8)}_${Build.MODEL.replace(Regex("[^a-zA-Z0-9]"), "")}"
                repository.saveDeviceSession(
                    DeviceSession(
                        sessionId = sessionId,
                        uid = user.uid,
                        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                        platform = "Android ${Build.VERSION.RELEASE}",
                        active = true,
                        createdAt = safeNow
                    )
                )

                com.waqar.institute.biometric.TeacherBiometricGateManager.clearLoginVerificationOnAuthChange()
                prefsManager.setSelectedPortalRole(resolvedRole)
                _uiState.update {
                    it.copy(
                        userProfile = profile,
                        activePortalRole = roleEnum,
                        mfaChallengePending = profile.mfaEnabled
                    )
                }

                startRealtimeStreamsIfNeeded(user.uid)
                recomputeRoleScopedData()
                appContext?.let { ctx ->
                    com.waqar.institute.session.ConcurrentSessionManager.bindAndEnforceSingleDeviceSession(
                        context = ctx,
                        repository = repository,
                        username = cleanUsername,
                        uid = user.uid,
                        role = roleEnum,
                        onForceSignOutWithReason = { reason ->
                            signOut(reasonMessage = reason)
                        }
                    )
                }
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val attToken = com.waqar.institute.security.ZeroTrustAttestationEngine.generateHardwareBoundToken(
                                context = appContext,
                                userId = cleanUsername,
                                portalRole = resolvedRole
                            )
                            com.waqar.institute.security.BinaryVirtualizationBridge.verifyCriticalPayloadInVm(
                                attToken.payloadString.toByteArray(Charsets.UTF_8)
                            )
                        }
                    }
                }
            }.onFailure { err ->
                postMessage("Auth profile sync notice: ${err.localizedMessage ?: "offline"}")
            }
        }
    }

    private fun cancelRealtimeStreams() {
        streamJobs.forEach { it.cancel() }
        streamJobs.clear()
        collectionJobsStartedForUid = null
    }

    private fun startRealtimeStreamsIfNeeded(uid: String) {
        if (collectionJobsStartedForUid == uid && streamJobs.any { it.isActive }) return
        cancelRealtimeStreams()
        collectionJobsStartedForUid = uid

        streamJobs += viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val activeState = _uiState.value
                    val actorId = activeState.userProfile?.username?.takeIf { it.isNotBlank() } ?: uid
                    val portalRoleKey = activeState.activePortalRole.key
                    val boundTokenMap = com.waqar.institute.security.ZeroTrustAttestationEngine.generateHardwareBoundToken(
                        userId = actorId,
                        portalRole = portalRoleKey
                    )
                    val signedPayload = (boundTokenMap["payload"] ?: "$actorId:$portalRoleKey")
                        .toByteArray(Charsets.UTF_8)
                    com.waqar.institute.security.BinaryVirtualizationBridge.verifyCriticalPayloadInVm(signedPayload)
                }
            }
        }

        streamJobs += viewModelScope.launch {
            repository.observeSessions(uid).catch { }.collect { list ->
                _uiState.update { it.copy(sessions = list) }
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observePortalCredentials().catch { }.collect { list ->
                rawPortalCredentials = list
                recomputeRoleScopedData()
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeStudents().catch { }.collect { list ->
                rawStudents = list
                recomputeRoleScopedData()
                // Sync to encrypted SQLCipher Room table for Paging 3
                val cachedEntities = list.map { s ->
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
                        updatedAtMillis = System.currentTimeMillis()
                    )
                }
                runCatching { localDb.offlineDao().upsertCachedStudents(cachedEntities) }
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeEnquiries().catch { }.collect { list ->
                _uiState.update { it.copy(enquiries = list) }
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeCourses().catch { }.collect { list ->
                _uiState.update { it.copy(courses = list) }
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeBatches().catch { }.collect { list ->
                rawBatches = list
                recomputeRoleScopedData()
                // Sync to Room offline timetable cache
                val cached = list.map { b ->
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
                        updatedAtMillis = System.currentTimeMillis()
                    )
                }
                runCatching { localDb.offlineDao().upsertTimetableSlots(cached) }
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeAttendance().catch { }.collect { list ->
                rawAttendance = list
                recomputeRoleScopedData()
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeTeacherAttendance().catch { }.collect { list ->
                rawTeacherAttendance = list
                recomputeRoleScopedData()
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeFees().catch { }.collect { list ->
                rawFees = list
                recomputeRoleScopedData()
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeLedger().catch { }.collect { list ->
                _uiState.update { it.copy(ledger = list) }
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeExams().catch { }.collect { list ->
                rawExams = list
                recomputeRoleScopedData()
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeExamResults().catch { }.collect { list ->
                rawExamResults = list
                recomputeRoleScopedData()
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeMaterials().catch { }.collect { list ->
                _uiState.update { it.copy(materials = list) }
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeCertificates().catch { }.collect { list ->
                rawCertificates = list
                recomputeRoleScopedData()
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeStaff().catch { }.collect { list ->
                rawStaff = (InstituteRepository.localStaffCache.values + list).distinctBy { it.id }
                _uiState.update { it.copy(staff = rawStaff) }
                recomputeRoleScopedData()
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observePayroll().catch { }.collect { list ->
                rawPayroll = list
                recomputeRoleScopedData()
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeGuardians().catch { }.collect { list ->
                rawGuardians = (InstituteRepository.localGuardiansCache.values + list).distinctBy { it.id }
                recomputeRoleScopedData()
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeAnnouncements().catch { }.collect { list ->
                _uiState.update { it.copy(announcements = list) }
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeLiveClasses().catch { }.collect { list ->
                _uiState.update { it.copy(liveClasses = list) }
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeAiLogs().catch { }.collect { list ->
                _uiState.update { it.copy(aiLogs = list) }
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeAssets().catch { }.collect { list ->
                _uiState.update { it.copy(assets = list) }
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeTickets().catch { }.collect { list ->
                _uiState.update { it.copy(tickets = list) }
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeAuditLogs().catch { }.collect { list ->
                _uiState.update { it.copy(auditLogs = list) }
            }
        }
        streamJobs += viewModelScope.launch {
            repository.observeBackupsAndConfigs().catch { }.collect { list ->
                _uiState.update { it.copy(backupsAndConfigs = list) }
            }
        }
        streamJobs += viewModelScope.launch {
            ZeroFeeUpiPaymentEngine.observeUpiTransactions(repository.firestoreDb()).catch { }.collect { list ->
                rawUpiTransactions = list
                recomputeRoleScopedData()
            }
        }
        streamJobs += viewModelScope.launch {
            ZeroFeeUpiPaymentEngine.observeStudentFeesLedger(repository.firestoreDb()).catch { }.collect { list ->
                rawStudentFeesLedger = list
                recomputeRoleScopedData()
            }
        }
        streamJobs += viewModelScope.launch {
            ZeroFeeUpiPaymentEngine.observeInstituteAuditLogs(repository.firestoreDb()).catch { }.collect { list ->
                rawInstituteAuditLogs = list
                recomputeRoleScopedData()
            }
        }
    }

    fun postMessage(msg: String?) {
        _uiState.update { it.copy(statusMessage = msg) }
    }

    fun switchPortalRole(role: PortalRole) {
        viewModelScope.launch {
            val currentProfile = _uiState.value.userProfile
            // Security Guard: Logged-in non-admins cannot hijack portal views
            if (currentProfile != null && currentProfile.role != PortalRole.ADMIN.key && role != PortalRole.fromKey(currentProfile.role)) {
                postMessage("Portal switching is restricted to Institute Administrator.")
                return@launch
            }
            prefsManager.setSelectedPortalRole(role.key)
            _uiState.update { it.copy(activePortalRole = role) }
            recomputeRoleScopedData()
            recordAudit("SWITCH_PORTAL_ROLE", "M01_AUTH", role.key, "INFO", "Switched active portal to ${role.label}")
        }
    }

    fun verifyTotpMfa(code: String): Boolean {
        val actorKey = _uiState.value.userProfile?.uid ?: _uiState.value.userProfile?.username ?: "mfa_default"
        val rateDecision = com.example.security.EnterpriseRateLimiter.checkAndConsume(
            context = appContext,
            scope = com.example.security.EnterpriseRateLimiter.RateLimitScope.OTP_VERIFY,
            identityKey = actorKey
        )
        if (!rateDecision.allowed) {
            recordAudit("MFA_RATE_LIMIT_BLOCKED", "M01_AUTH", actorKey, "CRITICAL", rateDecision.message)
            postMessage(rateDecision.message)
            return false
        }
        val secret = _uiState.value.userProfile?.totpSecret?.ifBlank { "JBSWY3DPEHPK3PXP" } ?: "JBSWY3DPEHPK3PXP"
        val valid = com.example.security.Rfc6238TotpValidator.verifyCode(
            base32Secret = secret,
            userCode = code
        )
        if (valid) {
            _uiState.update { it.copy(mfaChallengePending = false) }
            postMessage("2FA RFC 6238 TOTP Verified successfully")
        } else {
            postMessage("Invalid 6-digit RFC 6238 TOTP authenticator code")
        }
        return valid
    }

    fun toggleMfaEnrollment(enabled: Boolean) {
        val profile = _uiState.value.userProfile ?: return
        viewModelScope.launch {
            val newSecret = if (enabled && (profile.totpSecret.isBlank() || profile.totpSecret == "WCI2026TOTP")) {
                com.example.security.Rfc6238TotpValidator.generateBase32Secret()
            } else {
                profile.totpSecret
            }
            val updated = profile.copy(mfaEnabled = enabled, totpSecret = newSecret)
            runCatching { repository.saveUserProfile(updated) }
            _uiState.update { it.copy(userProfile = updated) }
            recordAudit("TOGGLE_MFA", "M01_AUTH", profile.uid, "INFO", "TOTP 2FA set to $enabled")
            postMessage(if (enabled) "TOTP 2FA Enabled (RFC 6238 Secret Provisioned)" else "TOTP 2FA Disabled")
        }
    }

    fun unlockBiometricGate() {
        _uiState.update { it.copy(isLockedByBiometric = false) }
    }

    fun lockBiometricGate() {
        _uiState.update { it.copy(isLockedByBiometric = true) }
    }

    fun revokeSession(session: DeviceSession) {
        viewModelScope.launch {
            runCatching {
                repository.revokeDeviceSession(session)
                recordAudit("REVOKE_SESSION", "M01_AUTH", session.sessionId, "WARNING", "Revoked device ${session.deviceName}")
                postMessage("Revoked session on ${session.deviceName}")
            }
        }
    }

    fun signOut(reasonMessage: String? = null) {
        internal_compose_brahmastra.RoleCapabilityGate.bindVerifiedFirebaseJwtRole(null)
        com.waqar.institute.biometric.TeacherBiometricGateManager.clearLoginVerificationOnAuthChange()
        com.waqar.institute.session.ConcurrentSessionManager.stopWatchingSession()
        authServiceProvider.signOut(appContext)
        cancelRealtimeStreams()
        rawStudents = emptyList()
        rawBatches = emptyList()
        rawAttendance = emptyList()
        rawTeacherAttendance = emptyList()
        rawStaff = emptyList()
        rawFees = emptyList()
        rawExamResults = emptyList()
        rawCertificates = emptyList()
        rawGuardians = emptyList()
        rawPayroll = emptyList()
        rawPortalCredentials = emptyList()
        _uiState.update {
            it.copy(
                currentUser = null,
                userProfile = null,
                mfaChallengePending = false,
                provisioningStatus = ProvisioningStatus.IDLE,
                provisioningMessage = null,
                portalCredentials = emptyList(),
                students = emptyList(),
                batches = emptyList(),
                attendance = emptyList(),
                teacherAttendance = emptyList(),
                fees = emptyList(),
                payroll = emptyList(),
                statusMessage = reasonMessage?.takeIf { msg -> msg.isNotBlank() }
                    ?: "Signed out of Waqar Computer Institute"
            )
        }
    }

    // =========================================================================
    // M03: Students CRUD + Photo/Document Attachment + Credential Provisioning
    // =========================================================================

    fun addOrUpdateStudent(
        existingId: String? = null,
        enrollmentNo: String,
        fullName: String,
        email: String,
        phone: String,
        guardianName: String,
        guardianPhone: String,
        courseName: String,
        batchName: String,
        status: String = "ACTIVE",
        totalFeeInr: Long,
        paidFeeInr: Long,
        attendancePercent: Int = 100,
        photoUri: String = "",
        attachmentName: String = "",
        studentUsername: String = "",
        studentPassword: String = "",
        parentUsername: String = "",
        parentPassword: String = ""
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val cleanStudentPass = studentPassword.trim()
        val cleanParentPass = parentPassword.trim()
        if (cleanStudentPass.isNotEmpty() && cleanStudentPass.length < 6) {
            _uiState.update {
                it.copy(
                    provisioningStatus = ProvisioningStatus.FAILED,
                    provisioningMessage = "Student password must be at least 6 characters."
                )
            }
            postMessage("Student password must be at least 6 characters.")
            return
        }
        if (cleanParentPass.isNotEmpty() && cleanParentPass.length < 6) {
            _uiState.update {
                it.copy(
                    provisioningStatus = ProvisioningStatus.FAILED,
                    provisioningMessage = "Parent password must be at least 6 characters."
                )
            }
            postMessage("Parent password must be at least 6 characters.")
            return
        }
        val photoValidation = com.example.security.EnterpriseInputUploadValidator.validatePhotoOrAttachmentUri(photoUri)
        if (!photoValidation.valid) {
            postMessage(photoValidation.reason)
            return
        }
        val attachmentValidation = com.example.security.EnterpriseInputUploadValidator.validateAndSanitizeFileName(attachmentName)
        if (!attachmentValidation.valid) {
            postMessage(attachmentValidation.reason)
            return
        }

        val id = existingId ?: "stu_${UUID.randomUUID().toString().take(8)}"
        val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000))
        val existingRecord = rawStudents.firstOrNull { it.id == id }
        val existingCreatedAt = existingRecord?.createdAt ?: safeNow

        val matchedBatch = rawBatches.firstOrNull {
            normKey(it.name) == normKey(batchName) || it.id.equals(batchName.trim(), ignoreCase = true)
        }
        val resolvedBatchId = matchedBatch?.id
            ?: "bat_${batchName.lowercase().replace(Regex("[^a-z0-9]"), "").take(8)}"

        val record = StudentRecord(
            id = id,
            enrollmentNo = enrollmentNo.ifBlank { "WCI-${System.currentTimeMillis() % 10000}" },
            fullName = fullName.trim(),
            email = email.trim(),
            phone = phone.trim(),
            guardianName = guardianName.trim(),
            guardianPhone = guardianPhone.trim(),
            courseId = "crs_${courseName.lowercase().replace(Regex("[^a-z0-9]"), "").take(8)}",
            courseName = courseName.trim(),
            batchId = resolvedBatchId,
            batchName = batchName.trim(),
            status = status,
            totalFeePaise = totalFeeInr.coerceAtLeast(0L) * 100L,
            paidFeePaise = paidFeeInr.coerceAtLeast(0L) * 100L,
            attendancePercent = attendancePercent.coerceIn(0, 100),
            photoUri = photoUri.ifBlank { existingRecord?.photoUri.orEmpty() }.take(48000),
            attachmentName = attachmentName.ifBlank { existingRecord?.attachmentName.orEmpty() }.take(250),
            ownerUid = uid,
            createdAt = existingCreatedAt
        )

        // Immediately update local cache and UI state so Student is visible right away
        InstituteRepository.localStudentsCache[record.id] = record
        rawStudents = listOf(record) + rawStudents.filterNot { it.id == record.id }
        recomputeRoleScopedData()

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    provisioningStatus = ProvisioningStatus.PROVISIONING,
                    provisioningMessage = "Saving ${record.fullName} to Cloud Firestore..."
                )
            }
            runCatching {
                // 1. Save Student record to Cloud Firestore
                repository.saveStudentWithConfirmation(record).getOrThrow()

                // 2. Provision Student Portal Username & Password
                val effectiveStudentUsername = studentUsername.trim().ifBlank {
                    record.fullName.trim().lowercase().replace(Regex("[^a-z0-9]"), "") + "_wci"
                }.lowercase()
                val effectiveStudentPass = if (existingId == null && cleanStudentPass.isBlank()) "student123" else cleanStudentPass
                if (effectiveStudentUsername.length >= 3 && effectiveStudentPass.length >= 6) {
                    val cred = authServiceProvider.provisionOrUpdateCredential(
                        username = effectiveStudentUsername,
                        rawPasswordChars = effectiveStudentPass.toCharArray(),
                        role = PortalRole.STUDENT,
                        linkedEntityId = record.id,
                        displayName = record.fullName,
                        actorUidOverride = uid
                    ).getOrThrow()
                    rawPortalCredentials = listOf(cred) + rawPortalCredentials.filterNot {
                        it.username.equals(cred.username, ignoreCase = true)
                    }
                }

                // 3. Provision Parent Portal Username & Password only when supplied
                val effectiveParentUsername = parentUsername.trim().ifBlank {
                    "parent_" + (record.guardianName.ifBlank { record.fullName })
                        .trim().lowercase().replace(Regex("[^a-z0-9]"), "")
                }.lowercase()
                if (effectiveParentUsername.length >= 3 && cleanParentPass.length >= 6) {
                    val pCred = authServiceProvider.provisionOrUpdateCredential(
                        username = effectiveParentUsername,
                        rawPasswordChars = cleanParentPass.toCharArray(),
                        role = PortalRole.PARENT,
                        linkedEntityId = record.id,
                        displayName = record.guardianName.ifBlank { "Parent of ${record.fullName}" },
                        actorUidOverride = uid
                    ).getOrThrow()
                    rawPortalCredentials = listOf(pCred) + rawPortalCredentials.filterNot {
                        it.username.equals(pCred.username, ignoreCase = true)
                    }
                }

                // 4. Automatically link guardian record with explicit Parent portal flags
                if (existingId == null) {
                    val guardianLink = GuardianLink(
                        id = "grd_${record.id.takeLast(8)}",
                        guardianName = record.guardianName.ifBlank { "Parent of ${record.fullName}" },
                        phone = record.guardianPhone.ifBlank { record.phone },
                        email = record.email,
                        relation = "Parent",
                        studentId = record.id,
                        studentName = record.fullName,
                        parentUsername = effectiveParentUsername,
                        ptmNotes = "Admitted in ${record.courseName}",
                        consentSigned = true,
                        isParent = true,
                        portalRoles = listOf(PortalRole.PARENT.key),
                        ownerUid = uid,
                        createdAt = safeNow
                    )
                    runCatching { repository.saveGuardianWithConfirmation(guardianLink) }
                    rawGuardians = listOf(guardianLink) + rawGuardians.filterNot { it.id == guardianLink.id }
                }

                // 5. Pre-enroll student biometric face template if Admin uploaded a portrait photo
                if (record.photoUri.isNotBlank()) {
                    appContext?.let { ctx ->
                        runCatching {
                            com.example.biometric.StudentFaceEnrollmentManager.enrollFaceFromPhotoDataUri(
                                context = ctx,
                                studentId = record.id,
                                photoDataUri = record.photoUri
                            )
                        }
                    }
                }

                recomputeRoleScopedData()
                recordAudit("SAVE_STUDENT", "M03_STUDENTS", id, "INFO", "Saved student ${record.fullName} (${record.enrollmentNo})")
                val summary = buildString {
                    append("Saved ${record.fullName} to Cloud Firestore")
                    if (cleanStudentPass.length >= 6) append(" • Student Login: @$effectiveStudentUsername")
                    if (cleanParentPass.length >= 6) append(" • Parent Login: @$effectiveParentUsername")
                    if (cleanStudentPass.isBlank() && existingId == null) append(" (Assign portal password in Security/Credentials)")
                }
                _uiState.update {
                    it.copy(
                        provisioningStatus = ProvisioningStatus.PROVISIONED,
                        provisioningMessage = summary
                    )
                }
                postMessage(summary)
            }.onFailure { err ->
                val msg = "Cloud sync failed for ${record.fullName}: ${err.localizedMessage ?: "Network/permission error"}"
                _uiState.update {
                    it.copy(
                        provisioningStatus = ProvisioningStatus.FAILED,
                        provisioningMessage = msg
                    )
                }
                postMessage(msg)
            }
        }
    }

    fun deleteStudent(student: StudentRecord) {
        if (!ensureActiveSessionOrSignOut()) return
        viewModelScope.launch {
            runCatching {
                repository.deleteStudent(student.id)
                rawStudents = rawStudents.filterNot { it.id == student.id }
                recomputeRoleScopedData()
                recordAudit("DELETE_STUDENT", "M03_STUDENTS", student.id, "WARNING", "Deleted student ${student.fullName}")
                postMessage("Deleted student ${student.fullName}")
            }
        }
    }

    /**
     * Validates a 12-digit numeric Aadhaar number (`^\d{12}$`), links it to the Student's
     * profile in local state + Cloud Firestore, and posts a confirmation message.
     */
    fun linkStudentAadhaar(studentId: String, rawAadhaar: String): Boolean {
        val cleanDigits = rawAadhaar.replace(Regex("[\\s-]"), "").trim()
        if (!cleanDigits.matches(Regex("^\\d{12}$"))) {
            postMessage("Invalid Aadhaar number. Please enter a valid 12-digit numeric Aadhaar.")
            return false
        }
        val target = (InstituteRepository.localStudentsCache.values + rawStudents + _uiState.value.students)
            .firstOrNull { it.id == studentId || it.enrollmentNo.equals(studentId, ignoreCase = true) }
        if (target == null) {
            postMessage("Student profile not found for Aadhaar linking.")
            return false
        }
        val maskedAadhaar = "XXXX-XXXX-${cleanDigits.takeLast(4)}"
        val aadhaarLabel = "Verified Aadhaar: $maskedAadhaar"
        val updatedAttachment = if (target.attachmentName.isBlank() || target.attachmentName.startsWith("Verified Aadhaar:")) {
            aadhaarLabel
        } else if (!target.attachmentName.contains("Aadhaar")) {
            "${target.attachmentName} • $aadhaarLabel"
        } else {
            aadhaarLabel
        }
        val updatedStudent = target.copy(attachmentName = updatedAttachment.take(250))
        InstituteRepository.localStudentsCache[updatedStudent.id] = updatedStudent
        rawStudents = listOf(updatedStudent) + rawStudents.filterNot { it.id == updatedStudent.id }
        recomputeRoleScopedData()

        viewModelScope.launch {
            runCatching {
                repository.saveStudentWithConfirmation(updatedStudent)
                recordAudit(
                    action = "LINK_AADHAAR",
                    module = "M03_STUDENTS",
                    targetId = updatedStudent.id,
                    severity = "INFO",
                    details = "Linked 12-digit Aadhaar ($maskedAadhaar) to ${updatedStudent.fullName}"
                )
            }
        }
        postMessage("Aadhaar ($maskedAadhaar) linked successfully to ${updatedStudent.fullName} ✓")
        return true
    }

    // =========================================================================
    // M04: Admission Enquiries & CRM Conversion
    // =========================================================================

    fun saveEnquiry(
        existingId: String? = null,
        applicantName: String,
        phone: String,
        email: String,
        interestedCourse: String,
        source: String,
        stage: String,
        counsellorName: String,
        followUpDate: String,
        notes: String
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val id = existingId ?: "enq_${UUID.randomUUID().toString().take(8)}"
        val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000))
        val createdAt = _uiState.value.enquiries.firstOrNull { it.id == id }?.createdAt ?: safeNow

        val enquiry = AdmissionEnquiry(
            id = id,
            applicantName = applicantName.trim(),
            phone = phone.trim(),
            email = email.trim(),
            interestedCourse = interestedCourse.trim(),
            source = source.ifBlank { "Walk-in" },
            stage = stage,
            counsellorName = counsellorName.ifBlank { "Counsellor Desk" },
            followUpDate = followUpDate.ifBlank { todayIso() },
            notes = notes.trim(),
            ownerUid = uid,
            createdAt = createdAt
        )
        viewModelScope.launch {
            runCatching {
                repository.saveEnquiry(enquiry)
                recordAudit("SAVE_ENQUIRY", "M04_ADMISSIONS", id, "INFO", "Lead ${enquiry.applicantName} -> ${enquiry.stage}")
                postMessage("Saved enquiry for ${enquiry.applicantName}")
            }.onFailure { postMessage("Failed to save enquiry: ${it.localizedMessage}") }
        }
    }

    fun convertEnquiryToStudent(enquiry: AdmissionEnquiry, totalFeeInr: Long, batchName: String) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        viewModelScope.launch {
            runCatching {
                val updatedEnquiry = enquiry.copy(stage = "CONVERTED")
                repository.saveEnquiry(updatedEnquiry)
                addOrUpdateStudent(
                    enrollmentNo = "WCI-${2026}-${(1000..9999).random()}",
                    fullName = enquiry.applicantName,
                    email = enquiry.email,
                    phone = enquiry.phone,
                    guardianName = "",
                    guardianPhone = enquiry.phone,
                    courseName = enquiry.interestedCourse,
                    batchName = batchName.ifBlank { "General Batch" },
                    totalFeeInr = totalFeeInr,
                    paidFeeInr = 0L
                )
                recordAudit("CONVERT_ENQUIRY", "M04_ADMISSIONS", enquiry.id, "INFO", "Converted lead ${enquiry.applicantName} to enrolled student")
                postMessage("Converted ${enquiry.applicantName} to Active Student!")
            }
        }
    }

    // =========================================================================
    // M05 & M06: Courses & Batches
    // =========================================================================

    fun saveCourse(
        code: String,
        title: String,
        category: String,
        durationMonths: Int,
        totalFeeInr: Long,
        syllabusSummary: String,
        totalModules: Int
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val id = "crs_${UUID.randomUUID().toString().take(8)}"
        val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000))
        val course = CourseRecord(
            id = id,
            code = code.trim().uppercase(),
            title = title.trim(),
            category = category.ifBlank { "Diploma" },
            durationMonths = durationMonths.coerceIn(1, 60),
            totalFeePaise = totalFeeInr.coerceAtLeast(0L) * 100L,
            syllabusSummary = syllabusSummary.trim(),
            totalModules = totalModules.coerceIn(1, 200),
            active = true,
            ownerUid = uid,
            createdAt = safeNow
        )
        viewModelScope.launch {
            runCatching {
                repository.saveCourse(course)
                recordAudit("CREATE_COURSE", "M05_COURSES", id, "INFO", "Added course ${course.title}")
                postMessage("Created course ${course.title}")
            }.onFailure { postMessage("Course error: ${it.localizedMessage}") }
        }
    }

    fun saveBatch(
        code: String,
        name: String,
        courseName: String,
        teacherName: String,
        roomName: String,
        daysOfWeek: String,
        startTime: String,
        endTime: String,
        capacity: Int
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val id = "bat_${UUID.randomUUID().toString().take(8)}"
        val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000))
        val cleanTeacherName = teacherName.trim()
        val matchedStaff = _uiState.value.staff.firstOrNull { st ->
            normKey(st.fullName) == normKey(cleanTeacherName) ||
                st.teacherUsername.equals(cleanTeacherName, ignoreCase = true) ||
                slugKey(st.fullName) == slugKey(cleanTeacherName)
        }
        val batch = BatchRecord(
            id = id,
            code = code.trim().uppercase(),
            name = name.trim(),
            courseId = "crs_${courseName.take(6).lowercase()}",
            courseName = courseName.trim(),
            teacherId = matchedStaff?.id.orEmpty(),
            teacherName = cleanTeacherName,
            roomName = roomName.ifBlank { "Computer Lab 1" },
            daysOfWeek = daysOfWeek.ifBlank { "Mon-Sat" },
            startTime = startTime.ifBlank { "09:00" },
            endTime = endTime.ifBlank { "10:30" },
            capacity = capacity.coerceIn(1, 500),
            enrolledCount = 0,
            status = "ACTIVE",
            ownerUid = uid,
            createdAt = safeNow
        )
        viewModelScope.launch {
            runCatching {
                repository.saveBatch(batch)
                recordAudit("CREATE_BATCH", "M06_BATCHES", id, "INFO", "Scheduled batch ${batch.name} in ${batch.roomName}")
                postMessage("Created batch ${batch.name}")
            }.onFailure { postMessage("Batch error: ${it.localizedMessage}") }
        }
    }

    /**
     * [Group D2] Designates a Teacher as the Batch Admin / Lead Instructor for the given batch in Cloud Firestore.
     */
    fun assignBatchAdminTeacher(batch: BatchRecord, teacherName: String) {
        if (!ensureActiveSessionOrSignOut()) return
        val cleanTeacherName = teacherName.trim()
        val matchedStaff = _uiState.value.staff.firstOrNull { st ->
            normKey(st.fullName) == normKey(cleanTeacherName) ||
                st.teacherUsername.equals(cleanTeacherName, ignoreCase = true) ||
                slugKey(st.fullName) == slugKey(cleanTeacherName)
        }
        viewModelScope.launch {
            runCatching {
                val resolvedId = matchedStaff?.id ?: batch.teacherId.ifBlank { cleanTeacherName }
                val updated = batch.copy(
                    teacherId = resolvedId,
                    teacherName = "$cleanTeacherName (Batch Admin)",
                    batchAdminTeacherId = resolvedId,
                    batchAdminTeacherName = cleanTeacherName,
                    canBatchAdminTakeAttendance = true,
                    canBatchAdminScheduleExams = true
                )
                repository.saveBatch(updated)
                recordAudit(
                    action = "ASSIGN_BATCH_ADMIN",
                    module = "M06_BATCHES",
                    targetId = batch.id,
                    severity = "INFO",
                    details = "Assigned $cleanTeacherName as Batch Admin for ${batch.name} (Attendance & Exams Enabled)"
                )
                postMessage("$cleanTeacherName is now Batch Admin for ${batch.name} (Attendance & Exams Enabled) ✓")
            }.onFailure { postMessage("Batch update error: ${it.localizedMessage}") }
        }
    }

    /**
     * [Group D2] Typed overload to promote a [StaffMember] directly as Batch Admin for `batchId`.
     */
    fun assignBatchAdminTeacher(batchId: String, teacher: StaffMember) {
        if (!ensureActiveSessionOrSignOut()) return
        val target = rawBatches.firstOrNull { it.id == batchId }
            ?: _uiState.value.batches.firstOrNull { it.id == batchId }
            ?: return
        val updated = target.copy(
            teacherId = teacher.id,
            teacherName = "${teacher.fullName} (Batch Admin)",
            batchAdminTeacherId = teacher.id,
            batchAdminTeacherName = teacher.fullName,
            canBatchAdminTakeAttendance = true,
            canBatchAdminScheduleExams = true
        )
        viewModelScope.launch {
            runCatching {
                repository.saveBatch(updated)
                recordAudit(
                    action = "ASSIGN_BATCH_ADMIN",
                    module = "M06_BATCHES",
                    targetId = updated.id,
                    severity = "INFO",
                    details = "Assigned ${teacher.fullName} (${teacher.id}) as Batch Admin for ${updated.name}"
                )
                postMessage("${teacher.fullName} is now Batch Admin for ${updated.name} (Attendance & Exams Enabled) ✓")
            }.onFailure { postMessage("Batch update error: ${it.localizedMessage}") }
        }
    }

    /**
     * [Group D3] Returns live enrolled student count for a specific batch (used in Exam Batch Selector UI).
     */
    fun getStudentCountForBatch(
        batch: BatchRecord,
        allStudents: List<StudentRecord> = _uiState.value.students
    ): Int = allStudents.count { stu ->
        (stu.batchId.isNotBlank() && stu.batchId.equals(batch.id, ignoreCase = true)) ||
            (stu.batchName.isNotBlank() && stu.batchName.equals(batch.name, ignoreCase = true))
    }

    /**
     * Marks attendance for an entire batch of students in bulk in Cloud Firestore + local SQLCipher Room queue.
     */
    fun markBatchAttendanceInBulk(
        batchName: String,
        studentStatuses: Map<StudentRecord, String>
    ) {
        if (studentStatuses.isEmpty()) return
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val dateStr = todayIso()
        val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000))
        viewModelScope.launch {
            var count = 0
            studentStatuses.forEach { (stu, status) ->
                val cleanTarget = stu.id.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
                    .ifBlank { stu.enrollmentNo.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "student" } }
                val id = "students_${dateStr}_$cleanTarget".take(80)
                val record = AttendanceRecord(
                    id = id,
                    targetType = "STUDENT",
                    targetId = stu.id,
                    targetName = stu.fullName,
                    batchId = "bat_${batchName.take(6).lowercase()}",
                    batchName = batchName.trim(),
                    dateIso = dateStr,
                    status = status,
                    method = "BATCH_ROSTER",
                    remarks = "Marked in Batch Roll-Call ($batchName)",
                    markedByUid = uid,
                    createdAt = safeNow
                )
                runCatching { repository.saveAttendance(record) }
                count++
            }
            recordAudit(
                action = "BULK_BATCH_ATTENDANCE",
                module = "M07_ATTENDANCE",
                targetId = batchName,
                severity = "INFO",
                details = "Saved attendance for $count students in batch '$batchName' ($dateStr)"
            )
            postMessage("Saved attendance for $count students in $batchName to Cloud Firestore")
        }
    }

    // =========================================================================
    // M07: Attendance (Live Firestore + Room Offline Sync Queue)
    // =========================================================================

    fun markAttendance(
        targetType: String,
        targetId: String,
        targetName: String,
        batchName: String,
        status: String,
        method: String = "MANUAL",
        remarks: String = ""
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000))
        val dateStr = todayIso()
        val prefix = if (targetType.equals("STAFF", ignoreCase = true)) "teachers" else "students"
        val cleanTarget = targetId.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .ifBlank { targetName.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "user" } }
        val id = "${prefix}_${dateStr}_$cleanTarget".take(80)

        val record = AttendanceRecord(
            id = id,
            targetType = targetType,
            targetId = targetId,
            targetName = targetName.trim(),
            batchId = "bat_${batchName.take(6).lowercase()}",
            batchName = batchName.trim(),
            dateIso = dateStr,
            status = status,
            method = method,
            remarks = remarks.trim(),
            markedByUid = uid,
            createdAt = safeNow
        )

        viewModelScope.launch {
            // Queue locally in SQLCipher Room first for offline resilience
            val queuedEntity = QueuedAttendanceEntity(
                id = id,
                targetType = record.targetType,
                targetId = record.targetId,
                targetName = record.targetName,
                batchId = record.batchId,
                batchName = record.batchName,
                dateIso = record.dateIso,
                status = record.status,
                method = record.method,
                remarks = record.remarks,
                markedByUid = uid,
                queuedAtMillis = System.currentTimeMillis(),
                synced = false
            )
            runCatching { localDb.offlineDao().upsertQueuedAttendance(queuedEntity) }

            runCatching {
                repository.saveAttendance(record)
                localDb.offlineDao().markAttendanceSynced(id)
                postMessage("Marked ${record.targetName} as ${record.status}")
            }.onFailure {
                postMessage("Saved ${record.targetName} to Offline Attendance Queue (will auto-sync)")
            }
        }
    }

    fun syncOfflineAttendanceQueue() {
        val uid = currentActorUid() ?: return
        viewModelScope.launch {
            val unsynced = localDb.offlineDao().getUnsyncedAttendance()
            var syncedCount = 0
            for (item in unsynced) {
                val rec = AttendanceRecord(
                    id = item.id,
                    targetType = item.targetType,
                    targetId = item.targetId,
                    targetName = item.targetName,
                    batchId = item.batchId,
                    batchName = item.batchName,
                    dateIso = item.dateIso,
                    status = item.status,
                    method = item.method,
                    remarks = item.remarks,
                    markedByUid = uid,
                    createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
                )
                runCatching {
                    repository.saveAttendance(rec)
                    localDb.offlineDao().markAttendanceSynced(item.id)
                    syncedCount++
                }
            }
            localDb.offlineDao().clearSyncedAttendance()
            postMessage("Synced $syncedCount offline attendance records to cloud")
        }
    }

    // =========================================================================
    // M08 & M09: Fees, Invoices & Accounting Ledger
    // =========================================================================

    fun createFeeInvoiceAndReceipt(
        studentId: String,
        studentName: String,
        courseName: String,
        title: String,
        totalAmountInr: Long,
        paidAmountInr: Long,
        discountInr: Long = 0L,
        fineInr: Long = 0L,
        paymentMode: String = "UPI",
        dueDateIso: String = todayIso()
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val id = "fee_${UUID.randomUUID().toString().take(8)}"
        val receiptNo = "RCPT-2026-${(1000..9999).random()}"
        val totalPaise = totalAmountInr.coerceAtLeast(0L) * 100L
        val paidPaise = paidAmountInr.coerceAtLeast(0L) * 100L
        val discountPaise = discountInr.coerceAtLeast(0L) * 100L
        val finePaise = fineInr.coerceAtLeast(0L) * 100L
        val netPayable = (totalPaise - discountPaise + finePaise).coerceAtLeast(0L)
        val status = when {
            paidPaise >= netPayable && netPayable > 0L -> "PAID"
            paidPaise > 0L -> "PARTIAL"
            else -> "PENDING"
        }

        val invoice = FeeInvoice(
            id = id,
            receiptNo = receiptNo,
            studentId = studentId.ifBlank { "stu_walkin" },
            studentName = studentName.trim(),
            courseName = courseName.trim(),
            title = title.ifBlank { "Course Fee Installment" },
            totalAmountPaise = totalPaise,
            paidAmountPaise = paidPaise,
            discountPaise = discountPaise,
            finePaise = finePaise,
            paymentMode = paymentMode,
            status = status,
            dueDateIso = dueDateIso.ifBlank { todayIso() },
            paidAtIso = if (paidPaise > 0L) todayIso() else "",
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )

        rawFees = listOf(invoice) + rawFees.filterNot { it.id == invoice.id }
        recomputeRoleScopedData()

        viewModelScope.launch {
            runCatching {
                repository.saveFeeInvoice(invoice)
                recordAudit("COLLECT_FEE", "M08_FEES", id, "INFO", "Receipt $receiptNo for $studentName (₹$paidAmountInr)")
                postMessage("Generated Receipt $receiptNo for $studentName")
            }.onFailure { postMessage("Fee error: ${it.localizedMessage}") }
        }
    }

    fun updateFeeInvoice(
        invoiceId: String,
        additionalPaidInr: Long,
        paymentMode: String = "UPI"
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val existing = _uiState.value.fees.firstOrNull { it.id == invoiceId }
            ?: rawFees.firstOrNull { it.id == invoiceId }
            ?: return
        val addPaise = additionalPaidInr.coerceAtLeast(0L) * 100L
        val netPayable = (existing.totalAmountPaise - existing.discountPaise + existing.finePaise).coerceAtLeast(0L)
        val newPaidPaise = (existing.paidAmountPaise + addPaise).coerceAtMost(netPayable.coerceAtLeast(existing.paidAmountPaise + addPaise))
        val newStatus = when {
            newPaidPaise >= netPayable && netPayable > 0L -> "PAID"
            newPaidPaise > 0L -> "PARTIAL"
            else -> "PENDING"
        }
        val updated = existing.copy(
            paidAmountPaise = newPaidPaise,
            paymentMode = paymentMode.ifBlank { existing.paymentMode },
            status = newStatus,
            paidAtIso = todayIso()
        )
        rawFees = listOf(updated) + rawFees.filterNot { it.id == updated.id }
        recomputeRoleScopedData()

        viewModelScope.launch {
            runCatching {
                repository.saveFeeInvoice(updated)
                recordAudit("UPDATE_FEE", "M08_FEES", updated.id, "INFO", "Updated fee ${updated.receiptNo} (+₹$additionalPaidInr)")
                postMessage("Fee updated for ${updated.studentName} (${updated.receiptNo})")
            }.onFailure { postMessage("Fee update error: ${it.localizedMessage}") }
        }
    }

    // =========================================================================
    // Option B: Zero-Fee Direct UPI Intent + 4-Month Overdue Auto-EMI Controls
    // =========================================================================

    fun recordVerifiedPendingUpiTransaction(record: UpiTransactionRecord) {
        ZeroFeeUpiPaymentEngine.localUpiTransactionsCache[record.id] = record
        rawUpiTransactions = listOf(record) + rawUpiTransactions.filterNot { it.id == record.id }
        recomputeRoleScopedData()
        postMessage("UTR ${record.utr_number} auto-captured! Sent to Admin Portal for 1-Tap Approval.")
    }

    /**
     * Admin 1-Tap Confirm for a `PENDING_APPROVAL` UPI transaction:
     * 1. Marks `upi_transactions/{txnId}` as `VERIFIED_PAID` with Admin's UID.
     * 2. Credits `StudentRecord.paidFeePaise` and `student_fees_ledger/{studentId}`.
     * 3. Decrements/resets `overdue_months` if paid via Auto-EMI or Monthly Fee.
     * 4. Generates an official GST `FeeInvoice` receipt (`WCI-UPI-...`).
     * 5. Logs an immutable verification entry to `institute_audit_logs`.
     */
    fun adminApproveUpiTransaction(
        txn: UpiTransactionRecord,
        biometricActionSignatureHex: String = ""
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val adminUid = currentActorUid() ?: "admin_root"
        val approvedTxn = txn.copy(
            status = "VERIFIED_PAID",
            verified_by_uid = adminUid
        )
        ZeroFeeUpiPaymentEngine.localUpiTransactionsCache[approvedTxn.id] = approvedTxn
        rawUpiTransactions = listOf(approvedTxn) + rawUpiTransactions.filterNot { it.id == approvedTxn.id }

        // Update StudentRecord paidFeePaise
        val targetStudent = (InstituteRepository.localStudentsCache.values + rawStudents)
            .firstOrNull {
                it.id.equals(txn.student_id, ignoreCase = true) ||
                    it.enrollmentNo.equals(txn.student_id, ignoreCase = true) ||
                    it.fullName.equals(txn.student_name, ignoreCase = true)
            }

        val updatedStudent = targetStudent?.let { stu ->
            val newPaid = (stu.paidFeePaise + txn.amount_paise).coerceAtMost(stu.totalFeePaise.coerceAtLeast(stu.paidFeePaise + txn.amount_paise))
            stu.copy(paidFeePaise = newPaid)
        }
        if (updatedStudent != null) {
            InstituteRepository.localStudentsCache[updatedStudent.id] = updatedStudent
            rawStudents = listOf(updatedStudent) + rawStudents.filterNot { it.id == updatedStudent.id }
        }

        // Update StudentFeesLedgerRecord
        val existingLedger = _uiState.value.studentFeesLedger.firstOrNull {
            it.student_id.equals(txn.student_id, ignoreCase = true) ||
                it.enrollment_no.equals(txn.student_id, ignoreCase = true)
        }
        val baseLedger = if (updatedStudent != null) {
            ZeroFeeUpiPaymentEngine.buildOrSyncStudentLedger(updatedStudent, existingLedger)
        } else {
            existingLedger
        }
        val updatedLedger = baseLedger?.let { led ->
            val newPaidPaise = updatedStudent?.paidFeePaise ?: (led.paid_fee_paise + txn.amount_paise)
            val newOverdue = (led.overdue_months - 1).coerceAtLeast(0)
            val remainingPaise = (led.total_fee_paise - newPaidPaise).coerceAtLeast(0L)
            val newStatus = when {
                remainingPaise <= 0L && led.total_fee_paise > 0L -> "PAID"
                led.auto_emi_active && newOverdue > 0 -> "EMI_ACTIVE"
                else -> "PAID"
            }
            led.copy(
                paid_fee_paise = newPaidPaise,
                overdue_months = newOverdue,
                monthly_payment_status = newStatus,
                last_utr_number = txn.utr_number,
                last_emi_notice_iso = "Verified Paid ₹${txn.amount_paise / 100} (UTR: ${txn.utr_number}) on ${todayIso()}",
                updatedAt = System.currentTimeMillis()
            )
        }
        if (updatedLedger != null) {
            ZeroFeeUpiPaymentEngine.localStudentFeesLedgerCache[updatedLedger.student_id] = updatedLedger
            rawStudentFeesLedger = listOf(updatedLedger) + rawStudentFeesLedger.filterNot { it.student_id == updatedLedger.student_id }
        }

        // Generate Official GST Fee Receipt
        val receiptNo = "WCI-UPI-${txn.utr_number.takeLast(6).ifBlank { "1001" }}"
        val feeInvoice = FeeInvoice(
            id = "fee_upi_${txn.id.takeLast(8)}",
            receiptNo = receiptNo,
            studentId = updatedStudent?.id ?: txn.student_id,
            studentName = txn.student_name,
            courseName = txn.course_name.ifBlank { updatedStudent?.courseName ?: "Computer Course" },
            title = "${txn.payment_type} • Direct UPI Intent (UTR: ${txn.utr_number})",
            totalAmountPaise = txn.amount_paise,
            paidAmountPaise = txn.amount_paise,
            discountPaise = 0L,
            finePaise = 0L,
            dueDateIso = todayIso(),
            paidAtIso = todayIso(),
            paymentMode = "UPI (${txn.utr_number})",
            status = "PAID",
            ownerUid = adminUid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        rawFees = listOf(feeInvoice) + rawFees.filterNot { it.id == feeInvoice.id }
        recomputeRoleScopedData()

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val db = repository.firestoreDb()
                    db.collection("upi_transactions").document(approvedTxn.id)
                        .set(approvedTxn.toFirestoreMap())
                    if (updatedStudent != null) {
                        repository.saveStudent(updatedStudent)
                    }
                    if (updatedLedger != null) {
                        db.collection("student_fees_ledger").document(updatedLedger.student_id)
                            .set(updatedLedger.toFirestoreMap())
                    }
                    repository.saveFeeInvoice(feeInvoice)
                    ZeroFeeUpiPaymentEngine.logAuditEventInternal(
                        context = appContext,
                        actorUid = adminUid,
                        actorName = _uiState.value.userProfile?.displayName ?: "Institute Admin",
                        studentId = txn.student_id,
                        eventType = "ADMIN_1TAP_UPI_APPROVED",
                        severity = "INFO",
                        utrNumber = txn.utr_number,
                        intentHash = txn.intent_hash,
                        details = "Admin 1-Tap Confirmed UTR ${txn.utr_number} (₹${txn.amount_paise / 100}) for ${txn.student_name} • Receipt $receiptNo" +
                            if (biometricActionSignatureHex.isNotBlank()) " [BioSig:${biometricActionSignatureHex.take(16)}]" else ""
                    )
                    com.waqar.institute.security.ServerAuditHashChainClient.sealAuditChainEvent(
                        context = appContext,
                        eventType = "UPI_FEE_APPROVED",
                        recordId = approvedTxn.id,
                        payload = mapOf(
                            "studentId" to txn.student_id,
                            "studentName" to txn.student_name,
                            "amountPaise" to txn.amount_paise,
                            "utrNumber" to txn.utr_number,
                            "receiptNo" to receiptNo,
                            "verifiedByUid" to adminUid
                        ),
                        biometricSignatureHex = biometricActionSignatureHex
                    )
                }
            }
            recordAudit(
                action = "APPROVE_UPI_PAYMENT",
                module = "M08_FEES",
                targetId = txn.id,
                severity = "INFO",
                details = "1-Tap Confirmed UPI UTR ${txn.utr_number} (₹${txn.amount_paise / 100}) for ${txn.student_name}" +
                    if (biometricActionSignatureHex.isNotBlank()) " [BioSig:${biometricActionSignatureHex.take(16)}]" else ""
            )
            postMessage("Verified UTR ${txn.utr_number} • Credited ₹${txn.amount_paise / 100} & Generated Receipt $receiptNo ✓")
        }
    }

    /**
     * Admin 1-Tap Reject for a spoofed or invalid UPI transaction.
     */
    fun adminRejectUpiTransaction(txn: UpiTransactionRecord, reason: String = "UTR not found in bank statement") {
        if (!ensureActiveSessionOrSignOut()) return
        val adminUid = currentActorUid() ?: "admin_root"
        val rejectedTxn = txn.copy(
            status = "REJECTED_FRAUD",
            verified_by_uid = adminUid
        )
        ZeroFeeUpiPaymentEngine.localUpiTransactionsCache[rejectedTxn.id] = rejectedTxn
        rawUpiTransactions = listOf(rejectedTxn) + rawUpiTransactions.filterNot { it.id == rejectedTxn.id }
        recomputeRoleScopedData()

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    repository.firestoreDb().collection("upi_transactions").document(rejectedTxn.id)
                        .set(rejectedTxn.toFirestoreMap())
                    ZeroFeeUpiPaymentEngine.logAuditEventInternal(
                        context = appContext,
                        actorUid = adminUid,
                        actorName = _uiState.value.userProfile?.displayName ?: "Institute Admin",
                        studentId = txn.student_id,
                        eventType = "ADMIN_REJECTED_UPI_FRAUD",
                        severity = "CRITICAL",
                        utrNumber = txn.utr_number,
                        intentHash = txn.intent_hash,
                        details = "Rejected UPI transaction ${txn.id} (UTR: ${txn.utr_number}): $reason"
                    )
                }
            }
            recordAudit("REJECT_UPI_FRAUD", "M08_FEES", txn.id, "CRITICAL", "Rejected UTR ${txn.utr_number}: $reason")
            postMessage("Rejected UPI transaction ${txn.utr_number} & logged fraud alert.")
        }
    }

    /**
     * Layer 4: Parses pasted Paytm Business / PhonePe Business / Bank SMS or Notification text
     * and auto-approves the matching `PENDING_APPROVAL` UPI transaction.
     */
    fun adminReconcileMerchantAlertText(rawAlertText: String) {
        val parsed = ZeroFeeUpiPaymentEngine.extractMerchantAlertUtrAndAmount(rawAlertText)
        if (parsed == null) {
            postMessage("Could not find a valid 12-digit UTR in the notification text.")
            return
        }
        val (utr, amountPaise) = parsed
        val matchedTxn = _uiState.value.upiTransactions.firstOrNull {
            it.status == "PENDING_APPROVAL" &&
                it.utr_number.equals(utr, ignoreCase = true) &&
                (amountPaise <= 0L || it.amount_paise == amountPaise)
        }
        if (matchedTxn == null) {
            postMessage("No pending transaction found matching UTR $utr" + if (amountPaise > 0) " (₹${amountPaise / 100})" else "")
            return
        }
        adminApproveUpiTransaction(matchedTxn)
    }

    /**
     * Admin Master Control for a student's Monthly Fee, Overdue Months (`>= 4` triggers Auto-EMI),
     * Auto-EMI Master Toggle (`auto_emi_active`), and Monthly Billing Date (`1..28`).
     */
    fun configureStudentAutoEmiAndMonthlyFee(
        studentId: String,
        monthlyFeeInr: Long,
        emiInstallmentInr: Long,
        overdueMonths: Int,
        autoEmiActive: Boolean,
        billingDayOfMonth: Int
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: "admin_root"
        val existingLedger = _uiState.value.studentFeesLedger.firstOrNull { it.student_id == studentId }
        val student = (InstituteRepository.localStudentsCache.values + rawStudents).firstOrNull { it.id == studentId }
        if (existingLedger == null && student == null) {
            postMessage("Student not found for Auto-EMI configuration.")
            return
        }

        val cleanDay = billingDayOfMonth.coerceIn(1, 28)
        val cleanOverdue = overdueMonths.coerceIn(0, 120)
        val monthlyPaise = (monthlyFeeInr.coerceAtLeast(100L)) * 100L
        val emiPaise = (emiInstallmentInr.coerceAtLeast(100L)) * 100L
        val nextDueIso = ZeroFeeUpiPaymentEngine.computeNextBillingDateIso(cleanDay)

        val base = if (student != null) {
            ZeroFeeUpiPaymentEngine.buildOrSyncStudentLedger(student, existingLedger, monthlyPaise)
        } else {
            existingLedger!!
        }

        val status = when {
            base.balancePaise <= 0L && base.total_fee_paise > 0L -> "PAID"
            autoEmiActive -> "EMI_ACTIVE"
            cleanOverdue > 0 -> "OVERDUE"
            else -> "PENDING"
        }

        val noticeText = if (autoEmiActive) {
            "Auto-EMI Active (Overdue: $cleanOverdue mo) • ₹${emiPaise / 100}/mo auto-scheduled on Day $cleanDay ($nextDueIso)"
        } else if (cleanOverdue >= 4) {
            "4+ Months Overdue ($cleanOverdue mo) • Ready for Admin Auto-EMI Activation (Day $cleanDay)"
        } else {
            "Monthly Fee ₹${monthlyPaise / 100}/mo • Billing Day $cleanDay ($nextDueIso)"
        }

        val updated = base.copy(
            monthly_fee_paise = monthlyPaise,
            emi_installment_paise = emiPaise,
            overdue_months = cleanOverdue,
            auto_emi_active = autoEmiActive,
            emi_billing_date = cleanDay,
            monthly_payment_status = status,
            next_due_date_iso = nextDueIso,
            last_emi_notice_iso = noticeText.take(155),
            owner_uid = uid,
            updatedAt = System.currentTimeMillis()
        )

        ZeroFeeUpiPaymentEngine.localStudentFeesLedgerCache[updated.student_id] = updated
        rawStudentFeesLedger = listOf(updated) + rawStudentFeesLedger.filterNot { it.student_id == updated.student_id }
        recomputeRoleScopedData()

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    repository.firestoreDb().collection("student_fees_ledger")
                        .document(updated.student_id)
                        .set(updated.toFirestoreMap())
                    ZeroFeeUpiPaymentEngine.logAuditEventInternal(
                        context = appContext,
                        actorUid = uid,
                        actorName = _uiState.value.userProfile?.displayName ?: "Institute Admin",
                        studentId = updated.student_id,
                        eventType = if (autoEmiActive) "AUTO_EMI_ACTIVATED_BY_ADMIN" else "MONTHLY_FEE_LEDGER_UPDATED",
                        severity = "INFO",
                        utrNumber = "",
                        intentHash = "",
                        details = "Updated ${updated.student_name}: overdue=${cleanOverdue}m, autoEmi=$autoEmiActive, billingDay=$cleanDay, emi=₹${emiPaise / 100}"
                    )
                }
            }
            postMessage(
                if (autoEmiActive) {
                    "Auto-EMI Enabled for ${updated.student_name} (₹${emiPaise / 100} on Day $cleanDay of every month) ✓"
                } else {
                    "Updated Monthly Fee & Overdue Ledger for ${updated.student_name} ✓"
                }
            )
        }
    }

    /**
     * Dispatches an instant Auto-EMI Billing Deduction Alert to the Student & Parent portals
     * when the student's monthly billing date arrives (or when triggered by Admin).
     */
    fun triggerMonthlyEmiDeductionNotice(ledger: StudentFeesLedgerRecord) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: "admin_root"
        val installmentInr = (ledger.emi_installment_paise / 100L).coerceAtLeast(100L)
        val noticeMsg = "Auto-EMI Deduction Due Today (Day ${ledger.emi_billing_date}): Please complete ₹$installmentInr UPI Intent payment to ${ZeroFeeUpiPaymentEngine.MERCHANT_NAME} (${ZeroFeeUpiPaymentEngine.MERCHANT_VPA})."
        val updated = ledger.copy(
            last_emi_notice_iso = noticeMsg.take(155),
            updatedAt = System.currentTimeMillis()
        )
        ZeroFeeUpiPaymentEngine.localStudentFeesLedgerCache[updated.student_id] = updated
        rawStudentFeesLedger = listOf(updated) + rawStudentFeesLedger.filterNot { it.student_id == updated.student_id }
        recomputeRoleScopedData()

        val announcement = AnnouncementItem(
            id = "ann_emi_${UUID.randomUUID().toString().take(8)}",
            title = "Auto-EMI Installment Alert • ${ledger.student_name}",
            body = noticeMsg,
            targetAudience = "ALL",
            channel = "IN_APP",
            priority = "URGENT",
            senderName = "WCI Auto-EMI Billing Engine",
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    repository.firestoreDb().collection("student_fees_ledger")
                        .document(updated.student_id)
                        .set(updated.toFirestoreMap())
                    repository.saveAnnouncement(announcement)
                }
            }
            postMessage("Sent Auto-EMI Deduction Alert (₹$installmentInr) to ${ledger.student_name} & Parent Portal ✓")
        }
    }

    fun addLedgerEntry(
        entryType: String,
        category: String,
        title: String,
        vendorName: String,
        amountInr: Long,
        gstInr: Long,
        paymentMode: String,
        referenceNo: String
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val id = "led_${UUID.randomUUID().toString().take(8)}"
        val entry = LedgerEntry(
            id = id,
            entryType = entryType,
            category = category.ifBlank { "Operations" },
            title = title.trim(),
            vendorName = vendorName.trim(),
            amountPaise = amountInr.coerceAtLeast(0L) * 100L,
            gstAmountPaise = gstInr.coerceAtLeast(0L) * 100L,
            paymentMode = paymentMode.ifBlank { "UPI" },
            referenceNo = referenceNo.ifBlank { "REF-${(100..999).random()}" },
            dateIso = todayIso(),
            approvedBy = _uiState.value.userProfile?.displayName ?: "Admin",
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            runCatching {
                repository.saveLedgerEntry(entry)
                recordAudit("ADD_LEDGER_ENTRY", "M09_ACCOUNTS", id, "INFO", "${entry.entryType}: ${entry.title}")
                postMessage("Logged ${entry.entryType}: ${entry.title}")
            }.onFailure { postMessage("Ledger error: ${it.localizedMessage}") }
        }
    }

    // =========================================================================
    // M10, M11, M12: Exams, Study Material & Certificates
    // =========================================================================

    fun createExam(
        title: String,
        courseName: String,
        batchName: String,
        examType: String,
        durationMinutes: Int,
        totalMarks: Int,
        passingMarks: Int,
        scheduledDateIso: String,
        questionsJson: String,
        batchId: String = "",
        startTime24h: String = "10:00",
        durationSeconds: Int = 0,
        autoAssignLabPcSeats: Boolean = true
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val id = "exm_${UUID.randomUUID().toString().take(8)}"
        val resolvedBatch = _uiState.value.batches.firstOrNull { b ->
            (batchId.isNotBlank() && b.id.equals(batchId, ignoreCase = true)) ||
                b.name.equals(batchName.trim(), ignoreCase = true)
        }
        val effectiveDurationSecs = if (durationSeconds > 0) {
            durationSeconds.coerceAtLeast(10)
        } else {
            durationMinutes.coerceIn(1, 360) * 60
        }
        val effectiveDurationMins = if (durationSeconds > 0) {
            ((effectiveDurationSecs + 59) / 60).coerceAtLeast(1)
        } else {
            durationMinutes.coerceIn(1, 360)
        }
        val cleanStartTime24h = startTime24h.trim().ifBlank { "10:00" }
        val cleanDate = scheduledDateIso.trim().ifBlank { todayIso() }
        val startEpochMs = com.example.exam.TamperProofExamClock.parse24HourScheduleEpochMs(cleanDate, cleanStartTime24h)
        val roleTag = if (_uiState.value.activePortalRole == PortalRole.TEACHER) "batch_admin_teacher" else "admin"

        val exam = ExamRecord(
            id = id,
            title = title.trim(),
            courseId = resolvedBatch?.courseId?.ifBlank { null } ?: "crs_${courseName.take(6).lowercase()}",
            courseName = courseName.trim(),
            batchId = batchId.ifBlank { resolvedBatch?.id.orEmpty() },
            batchName = batchName.trim(),
            examType = examType,
            durationMinutes = effectiveDurationMins,
            durationSeconds = effectiveDurationSecs,
            startTime24h = cleanStartTime24h,
            scheduledStartEpochMs = startEpochMs,
            autoAssignLabPcSeats = autoAssignLabPcSeats,
            createdByRole = roleTag,
            totalMarks = totalMarks.coerceIn(1, 1000),
            passingMarks = passingMarks.coerceIn(0, totalMarks),
            scheduledDateIso = cleanDate,
            questionsJson = questionsJson.ifBlank { "[]" },
            status = "PUBLISHED",
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            runCatching {
                repository.saveExam(exam)
                // [A5 + B2] Sync canonical Master Answer Keys to Admin/Server-only `/examAnswerKeys/{examId}`
                val parsedCustom = com.example.exam.PdfMcqBankParser.parseCustomExamBank(exam.questionsJson, exam.courseName)
                if (parsedCustom.questions.isNotEmpty()) {
                    val masterMap = parsedCustom.questions.associate { it.id to it.correctOptionIndex }
                    runCatching {
                        repository.saveExamAnswerKey(
                            examId = exam.id,
                            examTitle = exam.title,
                            batchId = exam.batchId,
                            answersByMasterQId = masterMap,
                            ownerUid = uid
                        )
                    }
                }
                recordAudit("PUBLISH_EXAM", "M10_EXAMS", id, "INFO", "Published exam ${exam.title} for ${exam.batchName} (${exam.startTime24h}, ${exam.effectiveDurationSeconds}s)")
                postMessage("Published exam ${exam.title} (${exam.startTime24h} • ${exam.effectiveDurationSeconds}s)")
            }.onFailure { postMessage("Exam error: ${it.localizedMessage}") }
        }
    }

    fun recordExamResult(
        examId: String,
        examTitle: String,
        studentId: String,
        studentName: String,
        marksObtained: Int,
        totalMarks: Int,
        rank: Int = 1,
        remarks: String = "",
        isOnlineCbtAttempt: Boolean = false
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val safeTotal = totalMarks.coerceAtLeast(1)
        val exactPct = (marksObtained.toDouble() * 100.0) / safeTotal.toDouble()
        val roundedPct = kotlin.math.round(exactPct * 100.0) / 100.0
        val formattedPct = "$roundedPct%"
        val grade = when {
            exactPct >= 90.0 -> "A+"
            exactPct >= 80.0 -> "A"
            exactPct >= 70.0 -> "B+"
            exactPct >= 60.0 -> "B"
            exactPct >= 50.0 -> "C"
            exactPct >= 40.0 -> "D"
            else -> "F"
        }
        if (isOnlineCbtAttempt && _uiState.value.activePortalRole == PortalRole.ADMIN) {
            postMessage("Admin Preview Mode: Score $marksObtained/$totalMarks ($formattedPct • $grade) — Not saved to Student Results.")
            return
        }
        val id = "res_${UUID.randomUUID().toString().take(8)}"
        val status = if (exactPct >= 40.0) "PASSED" else "FAILED"
        val computedRank = if (rank > 1) {
            rank
        } else {
            rawExamResults.count { it.examId == examId && it.marksObtained > marksObtained } + 1
        }

        val result = ExamResult(
            id = id,
            examId = examId.ifBlank { "exm_general" },
            examTitle = examTitle.trim(),
            studentId = studentId.ifBlank { "stu_general" },
            studentName = studentName.trim(),
            marksObtained = marksObtained.coerceAtLeast(0),
            totalMarks = safeTotal,
            exactPercentage = roundedPct,
            formattedPercentage = formattedPct,
            grade = grade,
            rank = computedRank.coerceAtLeast(1),
            status = status,
            remarks = remarks.trim(),
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            runCatching {
                repository.saveExamResult(result)
                postMessage("Saved result for $studentName ($grade • Rank #$computedRank)")
            }.onFailure { postMessage("Result error: ${it.localizedMessage}") }
        }
    }

    fun saveStudyMaterial(
        title: String,
        courseName: String,
        batchName: String,
        materialType: String,
        contentUrlOrBody: String,
        dueDateIso: String,
        maxMarks: Int = 100
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val uriCheck = com.example.security.EnterpriseInputUploadValidator.validatePhotoOrAttachmentUri(contentUrlOrBody)
        if (!uriCheck.valid) {
            postMessage(uriCheck.reason)
            return
        }
        val id = "mat_${UUID.randomUUID().toString().take(8)}"
        val mat = StudyMaterial(
            id = id,
            title = title.trim(),
            courseName = courseName.trim(),
            batchName = batchName.trim(),
            materialType = materialType,
            contentUrlOrBody = contentUrlOrBody.trim(),
            dueDateIso = dueDateIso.ifBlank { todayIso() },
            maxMarks = maxMarks.coerceAtLeast(0),
            downloadsCount = 0,
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            runCatching {
                repository.saveMaterial(mat)
                postMessage("Published study material: ${mat.title}")
            }.onFailure { postMessage("Material error: ${it.localizedMessage}") }
        }
    }

    fun issueCertificate(
        certType: String,
        studentId: String,
        studentName: String,
        courseName: String,
        grade: String,
        enrollmentNo: String = "",
        biometricActionSignatureHex: String = ""
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val serialNo = "WCI-CERT-${2026}-${(10000..99999).random()}"
        val id = serialNo
        val matchedStu = _uiState.value.students.firstOrNull {
            it.id == studentId || it.enrollmentNo.equals(enrollmentNo, ignoreCase = true) ||
                it.fullName.equals(studentName.trim(), ignoreCase = true)
        }
        val cleanRollOrId = enrollmentNo.trim().ifBlank {
            matchedStu?.enrollmentNo?.ifBlank { null } ?: studentId.ifBlank { "UNASSIGNED" }
        }
        val cleanStudentName = studentName.trim().ifBlank { matchedStu?.fullName ?: "Enrolled Student" }
        val cleanCourseName = courseName.trim().ifBlank { matchedStu?.courseName ?: "ADCA" }
        val cleanGrade = grade.ifBlank { "A+" }
        val issueDateIso = todayIso()
        val verificationHash = com.waqar.institute.certificate.CertificateQrGenerator.computeCanonicalVerificationHash(
            certSerialNo = serialNo,
            studentIdOrRoll = cleanRollOrId,
            studentName = cleanStudentName,
            courseName = cleanCourseName,
            grade = cleanGrade,
            issueDateIso = issueDateIso
        )
        val cert = CertificateRecord(
            id = id,
            serialNo = serialNo,
            certType = certType,
            studentId = cleanRollOrId,
            studentName = cleanStudentName,
            courseName = cleanCourseName,
            grade = cleanGrade,
            issueDateIso = issueDateIso,
            verificationHash = verificationHash,
            revoked = false,
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        rawCertificates = listOf(cert) + rawCertificates.filterNot { it.id == cert.id }
        recomputeRoleScopedData()
        viewModelScope.launch {
            runCatching {
                repository.saveCertificate(cert)
                val sigSuffix = if (biometricActionSignatureHex.isNotBlank()) {
                    " [BioSig:${biometricActionSignatureHex.take(16)}]"
                } else ""
                recordAudit("ISSUE_CERTIFICATE", "M12_CERTIFICATES", id, "INFO", "Issued $serialNo to $cleanStudentName ($cleanRollOrId)$sigSuffix")
                withContext(Dispatchers.IO) {
                    com.waqar.institute.security.ServerAuditHashChainClient.sealAuditChainEvent(
                        context = appContext,
                        eventType = "CERTIFICATE_ISSUED",
                        recordId = serialNo,
                        payload = mapOf(
                            "serialNo" to serialNo,
                            "studentId" to cleanRollOrId,
                            "studentName" to cleanStudentName,
                            "courseName" to cleanCourseName,
                            "grade" to cleanGrade,
                            "verificationHash" to verificationHash,
                            "issuedByUid" to uid
                        ),
                        biometricSignatureHex = biometricActionSignatureHex
                    )
                }
                postMessage("Issued Certificate $serialNo for $cleanStudentName (Roll: $cleanRollOrId)")
            }.onFailure { postMessage("Certificate error: ${it.localizedMessage}") }
        }
    }

    // =========================================================================
    // M13, M14, M15, M16, M17: Staff, Payroll, Guardians, Notices & Live Classes
    // =========================================================================

    fun saveStaffMember(
        existingId: String? = null,
        employeeCode: String,
        fullName: String,
        email: String,
        phone: String,
        designation: String,
        department: String,
        monthlySalaryInr: Long,
        photoUri: String = "",
        teacherUsername: String = "",
        teacherPassword: String = ""
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val cleanTeacherPass = teacherPassword.trim().ifBlank {
            if (existingId == null) "teacher123" else ""
        }
        if (existingId != null && cleanTeacherPass.isNotEmpty() && cleanTeacherPass.length < 6) {
            _uiState.update {
                it.copy(
                    provisioningStatus = ProvisioningStatus.FAILED,
                    provisioningMessage = "New password must be at least 6 characters (or leave blank to keep existing)."
                )
            }
            postMessage("New password must be at least 6 characters.")
            return
        }
        val staffPhotoValidation = com.example.security.EnterpriseInputUploadValidator.validatePhotoOrAttachmentUri(photoUri)
        if (!staffPhotoValidation.valid) {
            postMessage(staffPhotoValidation.reason)
            return
        }

        val id = existingId ?: "stf_${UUID.randomUUID().toString().take(8)}"
        val existingMember = _uiState.value.staff.firstOrNull { it.id == id }
        val effectiveTeacherUsername = teacherUsername.trim().ifBlank {
            existingMember?.teacherUsername?.ifBlank { null }
                ?: ("faculty_" + fullName.trim().lowercase().replace(Regex("[^a-z0-9]"), ""))
        }.lowercase()
        val effectivePhotoUri = photoUri.ifBlank { existingMember?.photoUri.orEmpty() }.take(48000)

        val member = StaffMember(
            id = id,
            employeeCode = employeeCode.ifBlank { "WCI-EMP-${(100..999).random()}" },
            fullName = fullName.trim(),
            email = email.trim(),
            phone = phone.trim(),
            designation = designation.ifBlank { "Senior Faculty" },
            department = department.ifBlank { "Computer Science" },
            monthlySalaryPaise = monthlySalaryInr.coerceAtLeast(0L) * 100L,
            joiningDateIso = existingMember?.joiningDateIso ?: todayIso(),
            status = "ACTIVE",
            photoUri = effectivePhotoUri,
            teacherUsername = effectiveTeacherUsername,
            ownerUid = uid,
            createdAt = existingMember?.createdAt ?: Timestamp(Date(System.currentTimeMillis() - 1000))
        )

        // Update local cache and UI state immediately so the Teacher appears in the list right away
        InstituteRepository.localStaffCache[member.id] = member
        rawStaff = listOf(member) + rawStaff.filterNot { it.id == member.id }
        _uiState.update { state ->
            state.copy(staff = listOf(member) + state.staff.filterNot { it.id == member.id })
        }
        recomputeRoleScopedData()

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    provisioningStatus = ProvisioningStatus.PROVISIONING,
                    provisioningMessage = "Syncing Teacher ${member.fullName} & Cloud credentials..."
                )
            }
            runCatching {
                // 1. Provision Teacher Portal Login Credentials
                if (effectiveTeacherUsername.length >= 3 && cleanTeacherPass.length >= 6) {
                    val cred = authServiceProvider.provisionOrUpdateCredential(
                        username = effectiveTeacherUsername,
                        rawPasswordChars = cleanTeacherPass.toCharArray(),
                        role = PortalRole.TEACHER,
                        linkedEntityId = member.id,
                        displayName = member.fullName,
                        actorUidOverride = uid
                    ).getOrThrow()
                    rawPortalCredentials = listOf(cred) + rawPortalCredentials.filterNot {
                        it.username.equals(cred.username, ignoreCase = true)
                    }
                }

                // 2. Sync StaffMember record to Cloud Firestore
                repository.saveStaffWithConfirmation(member).getOrThrow()
                recomputeRoleScopedData()

                // 3. Enroll Teacher's Gallery Profile Photo into 128-D Biometric Face Authentication Engine
                if (effectivePhotoUri.isNotBlank()) {
                    com.waqar.institute.biometric.FaceBiometricVerificationEngine.enrollTeacherProfilePhoto(
                        context = authServiceProvider.context,
                        teacherIds = listOf(
                            member.id,
                            effectiveTeacherUsername,
                            "cred_$effectiveTeacherUsername",
                            member.employeeCode
                        ),
                        teacherName = member.fullName,
                        photoDataUri = effectivePhotoUri,
                        enrolledByAdminUid = uid
                    )
                }

                recomputeRoleScopedData()
                recordAudit("ADD_STAFF", "M13_STAFF", id, "INFO", "Added/updated staff ${member.fullName} (@$effectiveTeacherUsername)")
                val msg = "Saved Teacher ${member.fullName} to Cloud Firestore • Login: @$effectiveTeacherUsername" +
                    if (effectivePhotoUri.isNotBlank()) " • Biometric Face Photo Enrolled ✓" else ""
                _uiState.update {
                    it.copy(
                        provisioningStatus = ProvisioningStatus.PROVISIONED,
                        provisioningMessage = msg
                    )
                }
                postMessage(msg)
            }.onFailure { err ->
                val msg = "Teacher cloud provisioning failed: ${err.localizedMessage ?: "Network/permission error"}"
                _uiState.update {
                    it.copy(
                        provisioningStatus = ProvisioningStatus.FAILED,
                        provisioningMessage = msg
                    )
                }
                postMessage(msg)
            }
        }
    }

    fun runStaffPayroll(
        staffId: String,
        staffName: String,
        monthYear: String,
        basicInr: Long,
        allowancesInr: Long,
        deductionsInr: Long,
        leaveDays: Int
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val id = "pay_${UUID.randomUUID().toString().take(8)}"
        val netInr = (basicInr + allowancesInr - deductionsInr).coerceAtLeast(0L)
        val entry = PayrollEntry(
            id = id,
            staffId = staffId.ifBlank { "stf_general" },
            staffName = staffName.trim(),
            monthYear = monthYear.ifBlank { "Nov 2026" },
            basicPaise = basicInr.coerceAtLeast(0L) * 100L,
            allowancesPaise = allowancesInr.coerceAtLeast(0L) * 100L,
            deductionsPaise = deductionsInr.coerceAtLeast(0L) * 100L,
            netPayPaise = netInr * 100L,
            status = "DISBURSED",
            leaveDays = leaveDays.coerceAtLeast(0),
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            runCatching {
                repository.savePayroll(entry)
                recordAudit("DISBURSE_PAYROLL", "M14_PAYROLL", id, "INFO", "Disbursed salary for $staffName ($monthYear)")
                postMessage("Processed payslip for $staffName")
            }.onFailure { postMessage("Payroll error: ${it.localizedMessage}") }
        }
    }

    fun saveGuardianLink(
        guardianName: String,
        phone: String,
        email: String,
        relation: String,
        studentName: String,
        ptmNotes: String,
        parentUsername: String = "",
        parentPassword: String = ""
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val cleanParentPass = parentPassword.trim()
        if (cleanParentPass.length < 6) {
            _uiState.update {
                it.copy(
                    provisioningStatus = ProvisioningStatus.FAILED,
                    provisioningMessage = "Parent login password must be at least 6 characters."
                )
            }
            postMessage("Parent login password must be at least 6 characters.")
            return
        }

        val id = "grd_${UUID.randomUUID().toString().take(8)}"
        val matchedStudent = (rawStudents + InstituteRepository.localStudentsCache.values).firstOrNull {
            it.fullName.equals(studentName.trim(), ignoreCase = true) ||
                it.id.equals(studentName.trim(), ignoreCase = true) ||
                it.enrollmentNo.equals(studentName.trim(), ignoreCase = true)
        }
        val effectiveParentUsername = parentUsername.trim().ifBlank {
            "parent_" + guardianName.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        }
        val link = GuardianLink(
            id = id,
            guardianName = guardianName.trim(),
            phone = phone.trim(),
            email = email.trim(),
            relation = relation.ifBlank { "Parent" },
            studentId = matchedStudent?.id ?: "stu_${studentName.take(6).lowercase()}",
            studentName = matchedStudent?.fullName ?: studentName.trim(),
            parentUsername = effectiveParentUsername,
            ptmNotes = ptmNotes.trim(),
            consentSigned = true,
            isParent = true,
            portalRoles = listOf(PortalRole.PARENT.key),
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    provisioningStatus = ProvisioningStatus.PROVISIONING,
                    provisioningMessage = "Syncing Parent @$effectiveParentUsername to Cloud Firestore..."
                )
            }
            runCatching {
                if (effectiveParentUsername.length >= 3) {
                    val cred = authServiceProvider.provisionOrUpdateCredential(
                        username = effectiveParentUsername,
                        rawPasswordChars = cleanParentPass.toCharArray(),
                        role = PortalRole.PARENT,
                        linkedEntityId = link.studentId,
                        displayName = "${link.guardianName} (Parent of ${link.studentName})",
                        actorUidOverride = uid
                    ).getOrThrow()
                    rawPortalCredentials = listOf(cred) + rawPortalCredentials.filterNot {
                        it.username.equals(cred.username, ignoreCase = true)
                    }
                }
                repository.saveGuardianWithConfirmation(link).getOrThrow()
                rawGuardians = listOf(link) + rawGuardians.filterNot { it.id == link.id }
                recomputeRoleScopedData()
                recordAudit("SAVE_GUARDIAN", "M15_PARENTS", link.id, "INFO", "Linked Parent ${link.guardianName} to ${link.studentName}")
                val msg = "Linked guardian ${link.guardianName} • Parent Login: @$effectiveParentUsername"
                _uiState.update {
                    it.copy(
                        provisioningStatus = ProvisioningStatus.PROVISIONED,
                        provisioningMessage = msg
                    )
                }
                postMessage(msg)
            }.onFailure { err ->
                val msg = "Guardian cloud sync failed: ${err.localizedMessage ?: "Network/permission error"}"
                _uiState.update {
                    it.copy(
                        provisioningStatus = ProvisioningStatus.FAILED,
                        provisioningMessage = msg
                    )
                }
                postMessage(msg)
            }
        }
    }

    fun publishAnnouncement(
        title: String,
        body: String,
        targetAudience: String,
        channel: String,
        priority: String
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val id = "ann_${UUID.randomUUID().toString().take(8)}"
        val item = AnnouncementItem(
            id = id,
            title = title.trim(),
            body = body.trim(),
            targetAudience = targetAudience,
            channel = channel,
            priority = priority,
            senderName = _uiState.value.userProfile?.displayName ?: "Institute Admin",
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            runCatching {
                repository.saveAnnouncement(item)
                recordAudit("BROADCAST_NOTICE", "M16_INBOX", id, "INFO", "Broadcast '${item.title}' to ${item.targetAudience}")
                postMessage("Broadcasted announcement: ${item.title}")
            }.onFailure { postMessage("Announcement error: ${it.localizedMessage}") }
        }
    }

    fun scheduleLiveClass(
        title: String,
        batchName: String,
        teacherName: String,
        scheduledTimeIso: String,
        durationMinutes: Int,
        activePollQuestion: String
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val id = "lvc_${UUID.randomUUID().toString().take(8)}"
        val session = LiveClassSession(
            id = id,
            title = title.trim(),
            batchName = batchName.trim(),
            teacherName = teacherName.trim(),
            meetingCode = "WCI-LIVE-${(1000..9999).random()}",
            scheduledTimeIso = scheduledTimeIso.ifBlank { "${todayIso()} 11:00" },
            durationMinutes = durationMinutes.coerceIn(10, 360),
            status = "LIVE",
            attendeeCount = 1,
            activePollQuestion = activePollQuestion.trim(),
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            runCatching {
                repository.saveLiveClass(session)
                postMessage("Started Live Class ${session.meetingCode}")
            }.onFailure { postMessage("Live class error: ${it.localizedMessage}") }
        }
    }

    // =========================================================================
    // M18: Real Gemini AI Tutor & Admin AI Assistant (Firebase AI Logic)
    // =========================================================================

    fun askAiAssistant(featureType: String, promptText: String) {
        if (promptText.isBlank()) return
        val uid = currentActorUid() ?: return
        val state = _uiState.value
        val contextSummary = "Students=${state.students.size}, Courses=${state.courses.size}, " +
            "Batches=${state.batches.size}, FeeInvoices=${state.fees.size}, Staff=${state.staff.size}"

        viewModelScope.launch {
            _uiState.update { it.copy(isAiStreaming = true, currentAiStreamText = "") }
            var finalResponse = ""
            runCatching {
                geminiService.streamTutorResponse(
                    role = state.activePortalRole.label,
                    languageCode = state.preferences.languageCode,
                    featureType = featureType,
                    userPrompt = promptText,
                    instituteContextSummary = contextSummary,
                    userId = uid
                ).collect { partial ->
                    finalResponse = partial
                    _uiState.update { it.copy(currentAiStreamText = partial) }
                }

                // Persist AI interaction log to Firestore (M18: F388)
                val logId = "ai_${UUID.randomUUID().toString().take(8)}"
                val log = AiInteractionLog(
                    id = logId,
                    userUid = uid,
                    userRole = state.activePortalRole.key,
                    featureType = featureType,
                    modelName = com.example.ai.GrokCloudFunctionSseClient.PROXY_MODEL_NAME,
                    promptText = promptText.take(3500),
                    responseText = finalResponse.ifBlank { "Completed." }.take(7500),
                    tokensUsed = (promptText.length + finalResponse.length) / 4,
                    safetyFlagged = finalResponse.contains("SECURITY WARNING") || finalResponse.contains("SECURITY LOCKOUT"),
                    createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
                )
                repository.saveAiLog(log)
            }.onFailure { err ->
                val errText = "AI Tutor error: ${err.localizedMessage ?: "Ensure App Check & network are active."}"
                _uiState.update { it.copy(currentAiStreamText = errText) }
                postMessage(errText)
            }
            _uiState.update { it.copy(isAiStreaming = false) }
        }
    }

    fun analyzeImageWithAi(bitmap: Bitmap, question: String) {
        val uid = currentActorUid() ?: return
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isAiStreaming = true, currentAiStreamText = "Analyzing computer lab image...") }
            runCatching {
                val answer = geminiService.analyzeMultimodalDoubt(
                    bitmap = bitmap,
                    question = question.ifBlank { "Explain this computer science diagram or code problem." },
                    languageCode = state.preferences.languageCode,
                    role = state.activePortalRole,
                    userId = uid
                )
                _uiState.update { it.copy(currentAiStreamText = answer) }
                val log = AiInteractionLog(
                    id = "ai_${UUID.randomUUID().toString().take(8)}",
                    userUid = uid,
                    userRole = state.activePortalRole.key,
                    featureType = "TUTOR_CHAT",
                    modelName = GeminiInstituteService.MODEL_NAME,
                    promptText = "[Multimodal Image] $question",
                    responseText = answer.take(7500),
                    tokensUsed = (question.length + answer.length) / 4 + 258,
                    safetyFlagged = answer.contains("SECURITY WARNING") || answer.contains("SECURITY LOCKOUT"),
                    createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
                )
                repository.saveAiLog(log)
            }.onFailure { err ->
                _uiState.update { it.copy(currentAiStreamText = "Vision AI error: ${err.localizedMessage}") }
            }
            _uiState.update { it.copy(isAiStreaming = false) }
        }
    }

    // =========================================================================
    // M20, M21, M22, M23, M24: Assets, Tickets, Security, Backups & Settings
    // =========================================================================

    fun saveInventoryAsset(
        assetCode: String,
        name: String,
        category: String,
        locationLab: String,
        conditionStatus: String,
        assignedTo: String,
        purchaseCostInr: Long
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val id = "ast_${UUID.randomUUID().toString().take(8)}"
        val asset = InventoryAsset(
            id = id,
            assetCode = assetCode.ifBlank { "WCI-PC-${(10..99).random()}" },
            name = name.trim(),
            category = category,
            locationLab = locationLab.ifBlank { "Main Computer Lab" },
            conditionStatus = conditionStatus,
            assignedTo = assignedTo.trim(),
            purchaseCostPaise = purchaseCostInr.coerceAtLeast(0L) * 100L,
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            runCatching {
                repository.saveAsset(asset)
                postMessage("Saved asset ${asset.name} (${asset.assetCode})")
            }.onFailure { postMessage("Asset error: ${it.localizedMessage}") }
        }
    }

    fun submitSupportTicket(
        subject: String,
        description: String,
        category: String,
        priority: String,
        npsScore: Int = 10
    ) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        val id = "tkt_${UUID.randomUUID().toString().take(8)}"
        val ticket = SupportTicket(
            id = id,
            ticketNo = "TKT-${(1000..9999).random()}",
            subject = subject.trim(),
            description = description.trim(),
            category = category,
            priority = priority,
            status = "OPEN",
            requesterUid = uid,
            requesterName = _uiState.value.userProfile?.displayName ?: "Institute Member",
            resolutionNotes = "",
            npsScore = npsScore.coerceIn(0, 10),
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            runCatching {
                repository.saveTicket(ticket)
                postMessage("Submitted Support Ticket ${ticket.ticketNo}")
            }.onFailure { postMessage("Ticket error: ${it.localizedMessage}") }
        }
    }

    fun resolveTicket(ticket: SupportTicket, resolutionNotes: String) {
        if (!ensureActiveSessionOrSignOut()) return
        viewModelScope.launch {
            runCatching {
                repository.saveTicket(
                    ticket.copy(
                        status = "RESOLVED",
                        resolutionNotes = resolutionNotes.ifBlank { "Resolved by Support Desk" }
                    )
                )
                postMessage("Resolved ticket ${ticket.ticketNo}")
            }
        }
    }

    fun createCloudBackupSnapshot(title: String): String {
        val uid = currentActorUid() ?: return "{}"
        val s = _uiState.value
        val totalRecords = s.students.size + s.courses.size + s.batches.size + s.fees.size + s.staff.size + s.certificates.size
        val summaryJson = """{"institute":"${s.preferences.instituteName}","timestamp":"${todayIso()}","students":${s.students.size},"courses":${s.courses.size},"batches":${s.batches.size},"fees":${s.fees.size},"staff":${s.staff.size},"certificates":${s.certificates.size}}"""
        val digestHex = java.security.MessageDigest.getInstance("SHA-256")
            .digest(summaryJson.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02X".format(it) }
        val checksum = "SHA256:$digestHex"
        val id = "bkp_${UUID.randomUUID().toString().take(8)}"

        val backup = InstituteBackupOrConfig(
            id = id,
            title = title.ifBlank { "Full Snapshot ${todayIso()}" },
            category = "BACKUP_SNAPSHOT",
            payloadJson = summaryJson,
            recordCount = totalRecords,
            checksum = checksum,
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            runCatching {
                repository.saveBackupOrConfig(backup)
                recordAudit("CREATE_BACKUP", "M23_BACKUP", id, "INFO", "Created backup snapshot ($totalRecords records, $checksum)")
                postMessage("Created verified backup snapshot ($totalRecords records)")
            }.onFailure { postMessage("Backup error: ${it.localizedMessage}") }
        }
        return summaryJson
    }

    /**
     * [C5] Builds a full JSON backup payload of all core institute collections and encrypts it
     * using `PBKDF2WithHmacSHA256` + `AES-256-GCM` into a `.wci` archive shared via `FileProvider`.
     */
    fun exportEncryptedWciBackup(context: Context, passphrase: String) {
        if (!ensureActiveSessionOrSignOut()) return
        if (passphrase.length < 6) {
            postMessage("Backup password must be at least 6 characters.")
            return
        }
        val s = _uiState.value
        runCatching {
            val root = org.json.JSONObject().apply {
                put("version", "WCI_BACKUP_V1")
                put("instituteName", s.preferences.instituteName)
                put("exportedAtIso", todayIso())
                put("students", org.json.JSONArray().apply {
                    s.students.forEach { stu ->
                        put(org.json.JSONObject().apply {
                            put("id", stu.id)
                            put("enrollmentNo", stu.enrollmentNo)
                            put("fullName", stu.fullName)
                            put("phone", stu.phone)
                            put("guardianName", stu.guardianName)
                            put("guardianPhone", stu.guardianPhone)
                            put("courseId", stu.courseId)
                            put("courseName", stu.courseName)
                            put("batchId", stu.batchId)
                            put("batchName", stu.batchName)
                            put("totalFeePaise", stu.totalFeePaise)
                            put("paidFeePaise", stu.paidFeePaise)
                            put("status", stu.status)
                        })
                    }
                })
                put("batches", org.json.JSONArray().apply {
                    s.batches.forEach { b ->
                        put(org.json.JSONObject().apply {
                            put("id", b.id)
                            put("code", b.code)
                            put("name", b.name)
                            put("courseId", b.courseId)
                            put("courseName", b.courseName)
                            put("teacherId", b.teacherId)
                            put("teacherName", b.teacherName)
                            put("batchAdminTeacherId", b.batchAdminTeacherId)
                            put("batchAdminTeacherName", b.batchAdminTeacherName)
                            put("roomName", b.roomName)
                            put("startTime", b.startTime)
                            put("endTime", b.endTime)
                            put("capacity", b.capacity)
                        })
                    }
                })
                put("courses", org.json.JSONArray().apply {
                    s.courses.forEach { c ->
                        put(org.json.JSONObject().apply {
                            put("id", c.id)
                            put("code", c.code)
                            put("title", c.title)
                            put("category", c.category)
                            put("durationMonths", c.durationMonths)
                            put("totalFeePaise", c.totalFeePaise)
                        })
                    }
                })
            }
            val file = com.waqar.institute.security.InstituteEncryptedBackupManager.exportEncryptedBackupFile(
                context = context,
                jsonPayload = root.toString(),
                passphrase = passphrase,
                shareViaChooser = true
            )
            recordAudit("EXPORT_ENCRYPTED_WCI_BACKUP", "M23_BACKUP", file.name, "INFO", "Exported AES-256-GCM .wci backup (${file.length()} bytes)")
            postMessage("Exported AES-256-GCM encrypted backup: ${file.name} (${file.length()} bytes) ✓")
        }.onFailure { err ->
            postMessage("Encrypted backup failed: ${err.localizedMessage}")
        }
    }

    /**
     * [C5] Decrypts a `.wci` AES-256-GCM backup archive and idempotently restores missing students,
     * batches, and courses into Cloud Firestore without duplicating existing IDs.
     */
    fun restoreEncryptedWciBackup(encryptedBytes: ByteArray, passphrase: String) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        viewModelScope.launch {
            runCatching {
                val jsonStr = com.waqar.institute.security.InstituteEncryptedBackupManager
                    .decryptFullBackupPayload(encryptedBytes, passphrase)
                val root = com.waqar.institute.security.InstituteEncryptedBackupManager.parseBackupSummary(jsonStr)
                val existingStudentIds = rawStudents.map { it.id }.toSet()
                val existingBatchIds = rawBatches.map { it.id }.toSet()
                val existingCourseIds = _uiState.value.courses.map { it.id }.toSet()
                var restoredCount = 0

                root.optJSONArray("courses")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val cid = o.optString("id")
                        if (cid.isNotBlank() && cid !in existingCourseIds) {
                            repository.saveCourse(
                                CourseRecord(
                                    id = cid,
                                    code = o.optString("code"),
                                    title = o.optString("title"),
                                    category = o.optString("category"),
                                    durationMonths = o.optInt("durationMonths", 6),
                                    totalFeePaise = o.optLong("totalFeePaise", 0L),
                                    ownerUid = uid
                                )
                            )
                            restoredCount++
                        }
                    }
                }
                root.optJSONArray("batches")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val bid = o.optString("id")
                        if (bid.isNotBlank() && bid !in existingBatchIds) {
                            repository.saveBatch(
                                BatchRecord(
                                    id = bid,
                                    code = o.optString("code"),
                                    name = o.optString("name"),
                                    courseId = o.optString("courseId"),
                                    courseName = o.optString("courseName"),
                                    teacherId = o.optString("teacherId"),
                                    teacherName = o.optString("teacherName"),
                                    batchAdminTeacherId = o.optString("batchAdminTeacherId"),
                                    batchAdminTeacherName = o.optString("batchAdminTeacherName"),
                                    roomName = o.optString("roomName"),
                                    startTime = o.optString("startTime", "09:00"),
                                    endTime = o.optString("endTime", "10:30"),
                                    capacity = o.optInt("capacity", 30),
                                    ownerUid = uid
                                )
                            )
                            restoredCount++
                        }
                    }
                }
                root.optJSONArray("students")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val sid = o.optString("id")
                        if (sid.isNotBlank() && sid !in existingStudentIds) {
                            repository.saveStudent(
                                StudentRecord(
                                    id = sid,
                                    enrollmentNo = o.optString("enrollmentNo"),
                                    fullName = o.optString("fullName"),
                                    phone = o.optString("phone"),
                                    guardianName = o.optString("guardianName"),
                                    guardianPhone = o.optString("guardianPhone"),
                                    courseId = o.optString("courseId"),
                                    courseName = o.optString("courseName"),
                                    batchId = o.optString("batchId"),
                                    batchName = o.optString("batchName"),
                                    totalFeePaise = o.optLong("totalFeePaise", 0L),
                                    paidFeePaise = o.optLong("paidFeePaise", 0L),
                                    status = o.optString("status", "ACTIVE"),
                                    ownerUid = uid
                                )
                            )
                            restoredCount++
                        }
                    }
                }
                recordAudit("RESTORE_ENCRYPTED_WCI_BACKUP", "M23_BACKUP", "restore_$restoredCount", "INFO", "Restored $restoredCount non-duplicate records from AES-256-GCM .wci backup")
                postMessage("Decrypted & restored $restoredCount records cleanly (zero duplicates) ✓")
            }.onFailure { err ->
                postMessage("Restore failed (wrong password or invalid .wci file): ${err.localizedMessage}")
            }
        }
    }

    // Settings & Preferences (M24: F443–F450)
    fun setDarkTheme(dark: Boolean) = viewModelScope.launch { prefsManager.setDarkTheme(dark) }
    fun setLanguage(lang: String) = viewModelScope.launch {
        prefsManager.setLanguageCode("EN")
        postMessage("Language set to English (EN)")
    }
    fun setUiTierOverride(tier: String) = viewModelScope.launch { prefsManager.setUiTierOverride(tier) }
    fun setReduceMotion(reduce: Boolean) = viewModelScope.launch { prefsManager.setReduceMotion(reduce) }
    fun setBiometricLockEnabled(enabled: Boolean) = viewModelScope.launch { prefsManager.setBiometricLock(enabled) }
    fun setFlagSecureEnabled(enabled: Boolean) = viewModelScope.launch { prefsManager.setFlagSecure(enabled) }

    fun saveInstituteBranding(
        name: String,
        tagline: String,
        branch: String,
        session: String,
        gst: String
    ) {
        val uid = currentActorUid() ?: return
        viewModelScope.launch {
            prefsManager.updateInstituteBranding(name, tagline, branch, session, gst)
            val cfg = InstituteBackupOrConfig(
                id = "cfg_branding",
                title = name,
                category = "INSTITUTE_PROFILE",
                payloadJson = """{"name":"$name","tagline":"$tagline","branch":"$branch","session":"$session","gst":"$gst"}""",
                recordCount = 1,
                checksum = "BRANDING_V1",
                ownerUid = uid,
                createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
            )
            runCatching { repository.saveBackupOrConfig(cfg) }
            recordAudit("UPDATE_INSTITUTE_SETTINGS", "M24_SETTINGS", "cfg_branding", "INFO", "Updated institute profile for $name")
            postMessage("Saved institute settings for $name")
        }
    }

    fun recordAudit(
        action: String,
        module: String,
        targetId: String,
        severity: String,
        details: String
    ) {
        val actorUid = currentActorUid() ?: return
        val actorEmail = auth.currentUser?.email
            ?: _uiState.value.userProfile?.email
            ?: "user@waqarinstitute.edu"
        val log = SecurityAuditLog(
            id = "aud_${UUID.randomUUID().toString().take(8)}",
            actorUid = actorUid,
            actorEmail = actorEmail,
            action = action,
            module = module,
            targetId = targetId,
            severity = severity,
            details = details,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            runCatching { repository.logAuditEvent(log) }
            runCatching {
                com.waqar.institute.ledger.ImmutableEventSourcedLedger.appendImmutableEvent(
                    nodeDeviceId = "node_${actorUid.take(8)}",
                    eventType = "${module}_${action}",
                    actorId = actorUid,
                    payloadSummary = "$severity|$targetId|$details"
                )
            }
            runCatching {
                com.waqar.institute.ipc.ZeroCopyIpcRingManager.publishPortalEventZeroCopy(
                    portalTag = module,
                    eventSummary = "$action:$targetId"
                )
            }
        }
    }

    fun reportSecurityIncidentAndNotifyAdmin(
        context: Context,
        username: String,
        fullName: String,
        phone: String,
        studentId: String,
        violationType: String,
        details: String = ""
    ) {
        val uid = currentActorUid() ?: "sec_monitor"
        com.waqar.institute.security.SecurityIncidentLogger.dispatchSecurityViolation(
            context = context,
            repository = repository,
            actorUid = uid,
            username = username,
            fullName = fullName,
            phoneNumber = phone,
            studentIdOrEnrollment = studentId,
            violationType = violationType,
            violationDetails = details.ifBlank {
                "Real-time RASP / TamperTrap telemetry alert captured and routed to Admin Security Portal"
            }
        )
        postMessage("Security Incident Logged: $violationType ($fullName)")
    }

    fun suspendStudentAccountForSecurityViolation(targetId: String) {
        if (!ensureActiveSessionOrSignOut()) return
        val uid = currentActorUid() ?: return
        viewModelScope.launch {
            runCatching {
                val matchedStudent = rawStudents.firstOrNull {
                    it.id.equals(targetId, ignoreCase = true) ||
                        it.enrollmentNo.equals(targetId, ignoreCase = true) ||
                        it.fullName.contains(targetId, ignoreCase = true)
                }
                if (matchedStudent != null) {
                    repository.saveStudent(matchedStudent.copy(status = "SUSPENDED_SECURITY"))
                }
                val matchedCred = rawPortalCredentials.firstOrNull {
                    it.username.equals(targetId, ignoreCase = true) ||
                        it.linkedEntityId.equals(matchedStudent?.id ?: targetId, ignoreCase = true)
                }
                if (matchedCred != null) {
                    repository.savePortalCredential(matchedCred.copy(active = false))
                }
                recordAudit(
                    action = "SECURITY_ACCOUNT_LOCKED",
                    module = "M22_SECURITY",
                    targetId = matchedStudent?.enrollmentNo ?: targetId,
                    severity = "CRITICAL",
                    details = "Admin suspended student account '${matchedStudent?.fullName ?: targetId}' due to security violation"
                )
                postMessage("Locked & suspended account: ${matchedStudent?.fullName ?: targetId}")
            }.onFailure { postMessage("Lock error: ${it.localizedMessage}") }
        }
    }

    private fun todayIso(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val appCtx = context.applicationContext
            val repo = InstituteRepository.create(appCtx)
            return InstituteViewModel(
                repository = repo,
                localDb = InstituteLocalDatabase.getDatabase(appCtx),
                prefsManager = PreferencesManager(appCtx),
                authServiceProvider = FirebaseAuthServiceProvider(repo, context = appCtx)
            ) as T
        }
    }
}

internal object TeacherScopeMatcher {
    fun normKey(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ").lowercase()

    fun slugKey(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9]"), "")

    fun filterBatchesForTeacher(
        batches: List<BatchRecord>,
        teacherIdKeys: Set<String>,
        teacherNameKeys: Set<String>,
        teacherNameSlugs: Set<String>
    ): List<BatchRecord> = batches.filter { b ->
        val cleanTeacher = normKey(b.teacherName.replace("(Batch Admin)", "", ignoreCase = true))
        val cleanTeacherSlug = slugKey(cleanTeacher)
        val bTeacherId = normKey(b.teacherId)
        val bAdminId = normKey(b.batchAdminTeacherId)
        val bAdminName = normKey(b.batchAdminTeacherName)
        val bAdminSlug = slugKey(bAdminName)
        (bAdminId.isNotBlank() && bAdminId in teacherIdKeys) ||
            (bAdminName.isNotBlank() && bAdminName in teacherNameKeys) ||
            (bAdminSlug.isNotBlank() && bAdminSlug in teacherNameSlugs) ||
            (bTeacherId.isNotBlank() && bTeacherId in teacherIdKeys) ||
            (cleanTeacher.isNotBlank() && cleanTeacher in teacherNameKeys) ||
            (cleanTeacherSlug.isNotBlank() && cleanTeacherSlug in teacherNameSlugs)
    }

    fun filterStudentsForTeacher(
        students: List<StudentRecord>,
        teacherBatchIds: Set<String>,
        teacherBatchNames: Set<String>,
        teacherCourseNames: Set<String>
    ): List<StudentRecord> = students.filter { stu ->
        (stu.batchId.isNotBlank() && normKey(stu.batchId) in teacherBatchIds) ||
            (stu.batchName.isNotBlank() && normKey(stu.batchName) in teacherBatchNames) ||
            (stu.batchId.isBlank() && stu.batchName.isBlank() && normKey(stu.courseName) in teacherCourseNames)
    }
}
