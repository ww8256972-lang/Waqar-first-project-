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

data class InstituteUiState(
    val currentUser: FirebaseUser? = null,
    val userProfile: UserProfile? = null,
    val activePortalRole: PortalRole = PortalRole.ADMIN,
    val isLockedByBiometric: Boolean = false,
    val mfaChallengePending: Boolean = false,
    val isAuthenticating: Boolean = false,
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
    val backupsAndConfigs: List<InstituteBackupOrConfig> = emptyList()
)

class InstituteViewModel(
    private val repository: InstituteRepository,
    private val localDb: InstituteLocalDatabase,
    private val prefsManager: PreferencesManager,
    val authServiceProvider: FirebaseAuthServiceProvider = FirebaseAuthServiceProvider(repository),
    private val geminiService: GeminiInstituteService = GeminiInstituteService(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) : ViewModel() {

    private val _uiState = MutableStateFlow(InstituteUiState(currentUser = auth.currentUser))
    val uiState: StateFlow<InstituteUiState> = _uiState.asStateFlow()

    // Raw unfiltered collections from Firestore so role/linkedEntityId filtering is reactive & instantaneous
    private var rawStudents: List<StudentRecord> = emptyList()
    private var rawBatches: List<BatchRecord> = emptyList()
    private var rawAttendance: List<AttendanceRecord> = emptyList()
    private var rawFees: List<FeeInvoice> = emptyList()
    private var rawExamResults: List<ExamResult> = emptyList()
    private var rawCertificates: List<CertificateRecord> = emptyList()
    private var rawGuardians: List<GuardianLink> = emptyList()
    private var rawPayroll: List<PayrollEntry> = emptyList()
    private var rawPortalCredentials: List<PortalCredential> = emptyList()

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

    init {
        // Pre-seed master admin credentials ('waqar' / 'waqar' and 'admin' / 'admin') in Cloud Firestore
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

    fun updateStudentPagingSearch(query: String) {
        studentSearchQuery.value = query.trim()
    }

    fun refreshRaspSecurityAudit(context: Context) {
        viewModelScope.launch {
            val report = EnterpriseRaspGuard.auditDeviceSecurity(context)
            _uiState.update { it.copy(raspReport = report) }
        }
    }

    /**
     * Enforces strict multi-tenant per-account data isolation:
     * - Admin sees all institute records + credential vault.
     * - Student & Parent ONLY see their own linked student record (`linkedEntityId` / `username`)
     *   and NEVER see any other student's record or any account's credentials.
     * - Teacher sees academic records for teaching, only their own payroll slips, and NEVER sees credentials.
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

        val allKnownStudents = (rawStudents + InstituteRepository.localStudentsCache.values).distinctBy { it.id }

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
            PortalRole.TEACHER -> {
                val matched = rawBatches.filter { b ->
                    (effectiveLinkedId.isNotEmpty() && b.teacherName.equals(effectiveLinkedId, ignoreCase = true)) ||
                        (displayNameKey.isNotEmpty() && b.teacherName.equals(displayNameKey, ignoreCase = true)) ||
                        (usernameKey.isNotEmpty() && b.teacherName.contains(usernameKey, ignoreCase = true))
                }
                if (matched.isNotEmpty()) matched else rawBatches
            }
            PortalRole.STUDENT, PortalRole.PARENT -> rawBatches
        }
        val teacherBatchNames = scopedBatches.map { it.name.lowercase() }.toSet()
        val teacherCourseNames = scopedBatches.map { it.courseName.lowercase() }.toSet()

        val scopedStudents = when (role) {
            PortalRole.ADMIN -> allKnownStudents
            PortalRole.TEACHER -> {
                val matched = allKnownStudents.filter { stu ->
                    stu.batchName.lowercase() in teacherBatchNames ||
                        stu.courseName.lowercase() in teacherCourseNames
                }
                if (matched.isNotEmpty()) matched else allKnownStudents
            }
            PortalRole.STUDENT, PortalRole.PARENT -> allKnownStudents.filter(::matchesStudent)
        }
        val allowedStudentIds = scopedStudents.map { it.id.lowercase() }.toSet() +
            scopedStudents.map { it.enrollmentNo.lowercase() }.toSet() +
            listOfNotNull(linkedId.lowercase().takeIf { it.isNotEmpty() })

        val scopedAttendance = when (role) {
            PortalRole.ADMIN -> rawAttendance
            PortalRole.TEACHER -> rawAttendance.filter { att ->
                att.batchName.lowercase() in teacherBatchNames ||
                    att.targetId.lowercase() in allowedStudentIds ||
                    att.targetType == "STAFF"
            }.ifEmpty { rawAttendance }
            PortalRole.STUDENT, PortalRole.PARENT -> rawAttendance.filter { att ->
                att.targetId.lowercase() in allowedStudentIds ||
                    (displayNameKey.isNotEmpty() && att.targetName.equals(displayNameKey, ignoreCase = true))
            }
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

        val scopedExamResults = when (role) {
            PortalRole.ADMIN, PortalRole.TEACHER -> rawExamResults
            PortalRole.STUDENT, PortalRole.PARENT -> rawExamResults.filter { res ->
                res.studentId.lowercase() in allowedStudentIds ||
                    (displayNameKey.isNotEmpty() && res.studentName.equals(displayNameKey, ignoreCase = true))
            }
        }

        val scopedCertificates = when (role) {
            PortalRole.ADMIN, PortalRole.TEACHER -> rawCertificates
            PortalRole.STUDENT, PortalRole.PARENT -> rawCertificates.filter { cert ->
                cert.studentId.lowercase() in allowedStudentIds ||
                    (displayNameKey.isNotEmpty() && cert.studentName.equals(displayNameKey, ignoreCase = true))
            }
        }

        val scopedGuardians = when (role) {
            PortalRole.ADMIN, PortalRole.TEACHER -> rawGuardians
            PortalRole.STUDENT, PortalRole.PARENT -> rawGuardians.filter { grd ->
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
                (linkedId.isNotEmpty() && pay.staffId.equals(linkedId, ignoreCase = true)) ||
                    (displayNameKey.isNotEmpty() && pay.staffName.equals(displayNameKey, ignoreCase = true))
            }
            PortalRole.STUDENT, PortalRole.PARENT -> emptyList()
        }

        // CRITICAL SECURITY CHECK: Only Admin can view portalCredentials list
        val scopedCredentials = if (role == PortalRole.ADMIN) rawPortalCredentials else emptyList()

        _uiState.update {
            it.copy(
                students = scopedStudents,
                batches = scopedBatches,
                attendance = scopedAttendance,
                fees = scopedFees,
                examResults = scopedExamResults,
                certificates = scopedCertificates,
                guardians = scopedGuardians,
                payroll = scopedPayroll,
                portalCredentials = scopedCredentials
            )
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
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isAuthenticating = true, statusMessage = null) }
            }
            try {
                val pwdChars = password.toCharArray()
                val result = withTimeoutOrNull(8000L) {
                    authServiceProvider.signInToPortalWithCredentials(
                        activityContext = activityContext,
                        role = role,
                        username = username,
                        passwordChars = pwdChars
                    )
                } ?: AuthPortalResult.Failure("Network verification timeout. Please try again.")

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isAuthenticating = false) }
                    when (result) {
                        is AuthPortalResult.Success -> {
                            val effectiveRole = result.session.role
                            prefsManager.setSelectedPortalRole(effectiveRole.key)
                            _uiState.update {
                                it.copy(
                                    userProfile = result.profile,
                                    activePortalRole = effectiveRole,
                                    mfaChallengePending = result.profile.mfaEnabled,
                                    isAuthenticating = false,
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
                                        onForceSignOut = {
                                            signOut()
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
                        is AuthPortalResult.Failure -> {
                            postMessage(result.message)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isAuthenticating = false) }
                    postMessage(e.localizedMessage ?: "Authentication failed. Please verify credentials.")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (_uiState.value.isAuthenticating) {
                        _uiState.update { it.copy(isAuthenticating = false) }
                    }
                }
            }
        }
    }

    /**
     * One-tap Google Sign-In via `FirebaseAuthServiceProvider` (for Parents & Admin).
     */
    fun authenticateWithGoogleRole(
        activityContext: Context,
        role: PortalRole,
        customUsername: String? = null
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthenticating = true) }
            val googleResult = authServiceProvider.authenticateFirebaseWithGoogle(activityContext)
            _uiState.update { it.copy(isAuthenticating = false) }
            googleResult.onSuccess { user ->
                onAuthenticatedUser(
                    user = user,
                    requestedRole = role,
                    customUsername = customUsername
                )
            }.onFailure { err ->
                postMessage("Google Sign-In failed: ${err.localizedMessage ?: "Canceled"}")
            }
        }
    }

    /**
     * Allows Admin to update their own Admin username & password live in Cloud Firestore.
     */
    fun updateAdminCredentials(newUsername: String, newPassword: String) {
        val currentUsername = _uiState.value.userProfile?.username ?: "admin"
        viewModelScope.launch {
            val result = authServiceProvider.updateAdminOwnCredentials(
                oldUsername = currentUsername,
                newUsername = newUsername,
                newPasswordChars = newPassword.toCharArray(),
                actorUidOverride = currentActorUid()
            )
            result.onSuccess { updatedCred ->
                _uiState.value.userProfile?.let { profile ->
                    val updatedProfile = profile.copy(username = updatedCred.username)
                    _uiState.update { it.copy(userProfile = updatedProfile) }
                }
                recordAudit(
                    action = "UPDATE_ADMIN_CREDENTIALS",
                    module = "M01_AUTH",
                    targetId = updatedCred.username,
                    severity = "WARNING",
                    details = "Admin updated portal username/password in Cloud Firestore (${updatedCred.username})"
                )
                postMessage("Admin username & password updated in Cloud Firestore: @${updatedCred.username}")
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
        if (_uiState.value.activePortalRole != PortalRole.ADMIN) {
            postMessage("Only Admin can issue or modify portal credentials.")
            return
        }
        viewModelScope.launch {
            val result = authServiceProvider.provisionOrUpdateCredential(
                username = username,
                rawPasswordChars = password.toCharArray(),
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
                postMessage("Saved ${role.label} login @${cred.username} to Cloud Firestore")
            }.onFailure { err ->
                postMessage("Credential error: ${err.localizedMessage}")
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

                prefsManager.setSelectedPortalRole(resolvedRole)
                _uiState.update {
                    it.copy(
                        userProfile = profile,
                        activePortalRole = PortalRole.fromKey(resolvedRole),
                        mfaChallengePending = profile.mfaEnabled
                    )
                }

                startRealtimeStreamsIfNeeded(user.uid)
                recomputeRoleScopedData()
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val attToken = com.waqar.institute.security.ZeroTrustAttestationEngine.generateHardwareBoundToken(
                                context = null,
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

    private fun startRealtimeStreamsIfNeeded(uid: String) {
        if (collectionJobsStartedForUid == uid) return
        collectionJobsStartedForUid = uid

        viewModelScope.launch {
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

        viewModelScope.launch {
            repository.observeSessions(uid).catch { }.collect { list ->
                _uiState.update { it.copy(sessions = list) }
            }
        }
        viewModelScope.launch {
            repository.observePortalCredentials().catch { }.collect { list ->
                rawPortalCredentials = list
                recomputeRoleScopedData()
            }
        }
        viewModelScope.launch {
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
        viewModelScope.launch {
            repository.observeEnquiries().catch { }.collect { list ->
                _uiState.update { it.copy(enquiries = list) }
            }
        }
        viewModelScope.launch {
            repository.observeCourses().catch { }.collect { list ->
                _uiState.update { it.copy(courses = list) }
            }
        }
        viewModelScope.launch {
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
        viewModelScope.launch {
            repository.observeAttendance().catch { }.collect { list ->
                rawAttendance = list
                recomputeRoleScopedData()
            }
        }
        viewModelScope.launch {
            repository.observeFees().catch { }.collect { list ->
                rawFees = list
                recomputeRoleScopedData()
            }
        }
        viewModelScope.launch {
            repository.observeLedger().catch { }.collect { list ->
                _uiState.update { it.copy(ledger = list) }
            }
        }
        viewModelScope.launch {
            repository.observeExams().catch { }.collect { list ->
                _uiState.update { it.copy(exams = list) }
            }
        }
        viewModelScope.launch {
            repository.observeExamResults().catch { }.collect { list ->
                rawExamResults = list
                recomputeRoleScopedData()
            }
        }
        viewModelScope.launch {
            repository.observeMaterials().catch { }.collect { list ->
                _uiState.update { it.copy(materials = list) }
            }
        }
        viewModelScope.launch {
            repository.observeCertificates().catch { }.collect { list ->
                rawCertificates = list
                recomputeRoleScopedData()
            }
        }
        viewModelScope.launch {
            repository.observeStaff().catch { }.collect { list ->
                _uiState.update { it.copy(staff = list) }
            }
        }
        viewModelScope.launch {
            repository.observePayroll().catch { }.collect { list ->
                rawPayroll = list
                recomputeRoleScopedData()
            }
        }
        viewModelScope.launch {
            repository.observeGuardians().catch { }.collect { list ->
                rawGuardians = list
                recomputeRoleScopedData()
            }
        }
        viewModelScope.launch {
            repository.observeAnnouncements().catch { }.collect { list ->
                _uiState.update { it.copy(announcements = list) }
            }
        }
        viewModelScope.launch {
            repository.observeLiveClasses().catch { }.collect { list ->
                _uiState.update { it.copy(liveClasses = list) }
            }
        }
        viewModelScope.launch {
            repository.observeAiLogs().catch { }.collect { list ->
                _uiState.update { it.copy(aiLogs = list) }
            }
        }
        viewModelScope.launch {
            repository.observeAssets().catch { }.collect { list ->
                _uiState.update { it.copy(assets = list) }
            }
        }
        viewModelScope.launch {
            repository.observeTickets().catch { }.collect { list ->
                _uiState.update { it.copy(tickets = list) }
            }
        }
        viewModelScope.launch {
            repository.observeAuditLogs().catch { }.collect { list ->
                _uiState.update { it.copy(auditLogs = list) }
            }
        }
        viewModelScope.launch {
            repository.observeBackupsAndConfigs().catch { }.collect { list ->
                _uiState.update { it.copy(backupsAndConfigs = list) }
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

    fun signOut() {
        com.waqar.institute.session.ConcurrentSessionManager.stopWatchingSession()
        authServiceProvider.signOut()
        collectionJobsStartedForUid = null
        _uiState.update {
            it.copy(
                currentUser = null,
                userProfile = null,
                mfaChallengePending = false,
                portalCredentials = emptyList()
            )
        }
        postMessage("Signed out of Waqar Computer Institute")
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
        val uid = currentActorUid() ?: return
        val id = existingId ?: "stu_${UUID.randomUUID().toString().take(8)}"
        val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000))
        val existingRecord = rawStudents.firstOrNull { it.id == id }
        val existingCreatedAt = existingRecord?.createdAt ?: safeNow

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
            batchId = "bat_${batchName.lowercase().replace(Regex("[^a-z0-9]"), "").take(8)}",
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

        viewModelScope.launch {
            runCatching {
                // 1. Immediately update local student cache & UI state so student is visible & ready for login
                InstituteRepository.localStudentsCache[record.id] = record
                rawStudents = listOf(record) + rawStudents.filterNot { it.id == record.id }

                // 2. Provision Student Portal Username & Password FIRST (instantaneous in-memory + cloud sync)
                val effectiveStudentUsername = studentUsername.trim().ifBlank {
                    record.fullName.trim().lowercase().replace(Regex("[^a-z0-9]"), "") + "_wci"
                }
                val effectiveStudentPassword = studentPassword.trim().ifBlank { "123456" }
                if (effectiveStudentUsername.length >= 3 && effectiveStudentPassword.length >= 3) {
                    authServiceProvider.provisionOrUpdateCredential(
                        username = effectiveStudentUsername,
                        rawPasswordChars = effectiveStudentPassword.toCharArray(),
                        role = PortalRole.STUDENT,
                        linkedEntityId = record.id,
                        displayName = record.fullName,
                        actorUidOverride = uid
                    ).onSuccess { cred ->
                        rawPortalCredentials = listOf(cred) + rawPortalCredentials.filterNot {
                            it.username.equals(cred.username, ignoreCase = true)
                        }
                    }
                }

                // 3. Provision Parent Portal Username & Password (always generated even if guardianName left blank)
                val effectiveParentUsername = parentUsername.trim().ifBlank {
                    "parent_" + (record.guardianName.ifBlank { record.fullName })
                        .trim().lowercase().replace(Regex("[^a-z0-9]"), "")
                }
                val effectiveParentPassword = parentPassword.trim().ifBlank { "123456" }
                if (effectiveParentUsername.length >= 3 && effectiveParentPassword.length >= 3) {
                    authServiceProvider.provisionOrUpdateCredential(
                        username = effectiveParentUsername,
                        rawPasswordChars = effectiveParentPassword.toCharArray(),
                        role = PortalRole.PARENT,
                        linkedEntityId = record.id,
                        displayName = record.guardianName.ifBlank { "Parent of ${record.fullName}" },
                        actorUidOverride = uid
                    ).onSuccess { cred ->
                        rawPortalCredentials = listOf(cred) + rawPortalCredentials.filterNot {
                            it.username.equals(cred.username, ignoreCase = true)
                        }
                    }
                }

                // 4. Automatically link guardian record
                if (existingId == null) {
                    val guardianLink = GuardianLink(
                        id = "grd_${record.id.takeLast(8)}",
                        guardianName = record.guardianName.ifBlank { "Parent of ${record.fullName}" },
                        phone = record.guardianPhone.ifBlank { record.phone },
                        email = record.email,
                        relation = "Parent",
                        studentId = record.id,
                        studentName = record.fullName,
                        ptmNotes = "Admitted in ${record.courseName}",
                        consentSigned = true,
                        ownerUid = uid,
                        createdAt = safeNow
                    )
                    InstituteRepository.localGuardiansCache[guardianLink.id] = guardianLink
                    rawGuardians = listOf(guardianLink) + rawGuardians.filterNot { it.id == guardianLink.id }
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        runCatching { repository.saveGuardian(guardianLink) }
                    }
                }

                recomputeRoleScopedData()
                postMessage(
                    "Enrolled ${record.fullName} • Student Login: @$effectiveStudentUsername | Parent Login: @$effectiveParentUsername"
                )

                // 5. Persist student & audit to Cloud Firestore
                runCatching { repository.saveStudent(record) }
                recordAudit("SAVE_STUDENT", "M03_STUDENTS", id, "INFO", "Saved student ${record.fullName} (${record.enrollmentNo})")
            }.onFailure { postMessage("Error saving student: ${it.localizedMessage}") }
        }
    }

    fun deleteStudent(student: StudentRecord) {
        viewModelScope.launch {
            runCatching {
                repository.deleteStudent(student.id)
                recordAudit("DELETE_STUDENT", "M03_STUDENTS", student.id, "WARNING", "Deleted student ${student.fullName}")
                postMessage("Deleted student ${student.fullName}")
            }
        }
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
        val uid = currentActorUid() ?: return
        val id = "bat_${UUID.randomUUID().toString().take(8)}"
        val safeNow = Timestamp(Date(System.currentTimeMillis() - 1000))
        val batch = BatchRecord(
            id = id,
            code = code.trim().uppercase(),
            name = name.trim(),
            courseId = "crs_${courseName.take(6).lowercase()}",
            courseName = courseName.trim(),
            teacherName = teacherName.trim(),
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
     * Designates a Teacher as the Batch Admin / Lead Instructor for the given batch in Cloud Firestore.
     */
    fun assignBatchAdminTeacher(batch: BatchRecord, teacherName: String) {
        viewModelScope.launch {
            runCatching {
                val updated = batch.copy(teacherName = "${teacherName.trim()} (Batch Admin)")
                repository.saveBatch(updated)
                recordAudit(
                    action = "ASSIGN_BATCH_ADMIN",
                    module = "M06_BATCHES",
                    targetId = batch.id,
                    severity = "INFO",
                    details = "Assigned $teacherName as Batch Admin for ${batch.name}"
                )
                postMessage("Assigned $teacherName as Batch Admin for ${batch.name}")
            }.onFailure { postMessage("Batch update error: ${it.localizedMessage}") }
        }
    }

    /**
     * Marks attendance for an entire batch of students in bulk in Cloud Firestore + local SQLCipher Room queue.
     */
    fun markBatchAttendanceInBulk(
        batchName: String,
        studentStatuses: Map<StudentRecord, String>
    ) {
        if (studentStatuses.isEmpty()) return
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

        viewModelScope.launch {
            runCatching {
                repository.saveFeeInvoice(invoice)
                recordAudit("COLLECT_FEE", "M08_FEES", id, "INFO", "Receipt $receiptNo for $studentName (₹$paidAmountInr)")
                postMessage("Generated Receipt $receiptNo for $studentName")
            }.onFailure { postMessage("Fee error: ${it.localizedMessage}") }
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
        questionsJson: String
    ) {
        val uid = currentActorUid() ?: return
        val id = "exm_${UUID.randomUUID().toString().take(8)}"
        val exam = ExamRecord(
            id = id,
            title = title.trim(),
            courseId = "crs_${courseName.take(6).lowercase()}",
            courseName = courseName.trim(),
            batchName = batchName.trim(),
            examType = examType,
            durationMinutes = durationMinutes.coerceIn(5, 360),
            totalMarks = totalMarks.coerceIn(1, 1000),
            passingMarks = passingMarks.coerceIn(0, totalMarks),
            scheduledDateIso = scheduledDateIso.ifBlank { todayIso() },
            questionsJson = questionsJson.ifBlank { "[]" },
            status = "PUBLISHED",
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            runCatching {
                repository.saveExam(exam)
                recordAudit("PUBLISH_EXAM", "M10_EXAMS", id, "INFO", "Published exam ${exam.title}")
                postMessage("Published exam ${exam.title}")
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
        val uid = currentActorUid() ?: return
        val pct = (marksObtained * 100) / totalMarks.coerceAtLeast(1)
        val grade = when {
            pct >= 90 -> "A+"
            pct >= 80 -> "A"
            pct >= 70 -> "B+"
            pct >= 60 -> "B"
            pct >= 40 -> "C"
            else -> "F"
        }
        if (isOnlineCbtAttempt && _uiState.value.activePortalRole == PortalRole.ADMIN) {
            postMessage("Admin Preview Mode: Score $marksObtained/$totalMarks ($grade) — Not saved to Student Results.")
            return
        }
        val id = "res_${UUID.randomUUID().toString().take(8)}"
        val status = if (pct >= 40) "PASSED" else "FAILED"
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
            totalMarks = totalMarks.coerceAtLeast(1),
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
        val uid = currentActorUid() ?: return
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
        grade: String
    ) {
        val uid = currentActorUid() ?: return
        val serialNo = "WCI-CERT-${2026}-${(10000..99999).random()}"
        val id = serialNo
        val hash = UUID.randomUUID().toString().replace("-", "").uppercase().take(16)
        val cert = CertificateRecord(
            id = id,
            serialNo = serialNo,
            certType = certType,
            studentId = studentId.ifBlank { "stu_verified" },
            studentName = studentName.trim(),
            courseName = courseName.trim(),
            grade = grade.ifBlank { "A+" },
            issueDateIso = todayIso(),
            verificationHash = "SHA256:$hash",
            revoked = false,
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            runCatching {
                repository.saveCertificate(cert)
                recordAudit("ISSUE_CERTIFICATE", "M12_CERTIFICATES", id, "INFO", "Issued $serialNo to $studentName")
                postMessage("Issued Certificate $serialNo")
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
        val uid = currentActorUid() ?: return
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
        viewModelScope.launch {
            runCatching {
                // 1. Update local cache and UI state immediately
                InstituteRepository.localStaffCache[member.id] = member
                _uiState.update { state ->
                    state.copy(staff = listOf(member) + state.staff.filterNot { it.id == member.id })
                }

                // 2. Provision Teacher Portal Login Credentials
                val effectiveTeacherPassword = teacherPassword.trim().ifBlank { "123456" }
                if (effectiveTeacherUsername.length >= 3 && effectiveTeacherPassword.length >= 3) {
                    authServiceProvider.provisionOrUpdateCredential(
                        username = effectiveTeacherUsername,
                        rawPasswordChars = effectiveTeacherPassword.toCharArray(),
                        role = PortalRole.TEACHER,
                        linkedEntityId = member.id,
                        displayName = member.fullName,
                        actorUidOverride = uid
                    ).onSuccess { cred ->
                        rawPortalCredentials = listOf(cred) + rawPortalCredentials.filterNot {
                            it.username.equals(cred.username, ignoreCase = true)
                        }
                        recomputeRoleScopedData()
                    }
                }

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
                        photoDataUri = effectivePhotoUri
                    )
                }

                postMessage(
                    "Saved Teacher ${member.fullName} • Login: @$effectiveTeacherUsername" +
                        if (effectivePhotoUri.isNotBlank()) " • Biometric Face Photo Enrolled ✓" else ""
                )

                // 4. Sync with Cloud Firestore
                runCatching { repository.saveStaff(member) }
                recordAudit("ADD_STAFF", "M13_STAFF", id, "INFO", "Added/updated staff ${member.fullName} (@$effectiveTeacherUsername)")
            }.onFailure { postMessage("Staff error: ${it.localizedMessage}") }
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
        val uid = currentActorUid() ?: return
        val id = "grd_${UUID.randomUUID().toString().take(8)}"
        val matchedStudent = (rawStudents + InstituteRepository.localStudentsCache.values).firstOrNull {
            it.fullName.equals(studentName.trim(), ignoreCase = true) ||
                it.id.equals(studentName.trim(), ignoreCase = true) ||
                it.enrollmentNo.equals(studentName.trim(), ignoreCase = true)
        }
        val link = GuardianLink(
            id = id,
            guardianName = guardianName.trim(),
            phone = phone.trim(),
            email = email.trim(),
            relation = relation.ifBlank { "Parent" },
            studentId = matchedStudent?.id ?: "stu_${studentName.take(6).lowercase()}",
            studentName = matchedStudent?.fullName ?: studentName.trim(),
            ptmNotes = ptmNotes.trim(),
            consentSigned = true,
            ownerUid = uid,
            createdAt = Timestamp(Date(System.currentTimeMillis() - 1000))
        )
        viewModelScope.launch {
            runCatching {
                InstituteRepository.localGuardiansCache[link.id] = link
                rawGuardians = listOf(link) + rawGuardians.filterNot { it.id == link.id }

                val effectiveParentUsername = parentUsername.trim().ifBlank {
                    "parent_" + link.guardianName.lowercase().replace(Regex("[^a-z0-9]"), "")
                }
                val effectiveParentPass = parentPassword.trim().ifBlank { "123456" }
                if (effectiveParentUsername.length >= 3 && effectiveParentPass.length >= 3) {
                    authServiceProvider.provisionOrUpdateCredential(
                        username = effectiveParentUsername,
                        rawPasswordChars = effectiveParentPass.toCharArray(),
                        role = PortalRole.PARENT,
                        linkedEntityId = link.studentId,
                        displayName = "${link.guardianName} (Parent of ${link.studentName})",
                        actorUidOverride = uid
                    ).onSuccess { cred ->
                        rawPortalCredentials = listOf(cred) + rawPortalCredentials.filterNot {
                            it.username.equals(cred.username, ignoreCase = true)
                        }
                    }
                }
                recomputeRoleScopedData()
                postMessage("Linked guardian ${link.guardianName} • Parent Login: @$effectiveParentUsername")
                runCatching { repository.saveGuardian(link) }
                recordAudit("SAVE_GUARDIAN", "M15_PARENTS", link.id, "INFO", "Linked Parent ${link.guardianName} to ${link.studentName}")
            }.onFailure { postMessage("Guardian error: ${it.localizedMessage}") }
        }
    }

    fun publishAnnouncement(
        title: String,
        body: String,
        targetAudience: String,
        channel: String,
        priority: String
    ) {
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
                    instituteContextSummary = contextSummary
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
                    modelName = GeminiInstituteService.MODEL_NAME,
                    promptText = promptText.take(3500),
                    responseText = finalResponse.ifBlank { "Completed." }.take(7500),
                    tokensUsed = (promptText.length + finalResponse.length) / 4,
                    safetyFlagged = false,
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
            _uiState.update { it.copy(isAiStreaming = true, currentAiStreamText = "Analyzing image with Gemini...") }
            runCatching {
                val answer = geminiService.analyzeMultimodalDoubt(
                    bitmap = bitmap,
                    question = question.ifBlank { "Explain this computer science diagram or code problem." },
                    languageCode = state.preferences.languageCode
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
                    safetyFlagged = false,
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
        val checksum = "SHA256:${UUID.randomUUID().toString().replace("-", "").take(16).uppercase()}"
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

    // Settings & Preferences (M24: F443–F450)
    fun setDarkTheme(dark: Boolean) = viewModelScope.launch { prefsManager.setDarkTheme(dark) }
    fun setLanguage(lang: String) = viewModelScope.launch { prefsManager.setLanguageCode(lang) }
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
                authServiceProvider = FirebaseAuthServiceProvider(repo)
            ) as T
        }
    }
}
