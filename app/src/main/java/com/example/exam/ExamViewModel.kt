package com.example.exam

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ExamRecord
import com.example.data.model.ExamResult
import com.example.security.EnterpriseRaspGuard
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Proctored Online Examination Engine & 5 Anti-Cheating Rules (`ExamViewModel.kt`).
 *
 * Enforces the 5 Enterprise Anti-Cheating Rules:
 * 1. Rule 1 (App-Switch & Background Lock): Detects `ON_STOP` / backgrounding; warns on Strike 1
 *    and auto-submits the exam immediately on Strike 2 (`maxBackgroundStrikes = 2`).
 * 2. Rule 2 (Screenshot & Screen-Recording Block): Applies `WindowManager.LayoutParams.FLAG_SECURE`
 *    while the exam is active so screenshots, Google Lens, and screen-recording are blocked.
 * 3. Rule 3 (Focus-Loss, Multi-Window & Split-Screen Detection): Detects `isInMultiWindowMode`
 *    or window focus loss (`onWindowFocusChanged(false)`) and locks/flags the attempt.
 * 4. Rule 4 (Root, Magisk, Emulator & Integrity Verification): Verifies device posture via
 *    `SecurityConfig` & `PreExamSecurityGate` before unlocking the question paper.
 * 5. Rule 5 (Offline Encrypted Answer Caching & Auto-Sync): Saves every selected answer immediately
 *    via `ExamRepository.cacheAnswerLocally` and syncs via `WorkManager` if offline.
 */
data class ActiveProctoredExamState(
    val exam: ExamRecord? = null,
    val studentId: String = "",
    val studentName: String = "",
    val questions: List<ExamQuestionItem> = emptyList(),
    val sanitizedQuestions: List<SanitizedExamQuestion> = emptyList(),
    val currentQuestionIndex: Int = 0,
    val selectedAnswers: Map<String, Int> = emptyMap(),
    val backgroundStrikes: Int = 0,
    val maxAllowedStrikes: Int = 2,
    val focusLossWarnings: Int = 0,
    val isMultiWindowBlocked: Boolean = false,
    val isFlagSecureActive: Boolean = false,
    val isKioskLockTaskActive: Boolean = false,
    val isSubmitted: Boolean = false,
    val submittedResult: ExamResult? = null,
    val lastGradingOutcome: ExamGradingOutcome? = null,
    val securityAlertBanner: String? = null,
    val activeSessionNonce: String = "",
    val serverHardDeadlineAtMs: Long = 0L,
    val isReVerificationRequired: Boolean = false,
    val reVerificationPrompt: String? = null,
    val lastReVerificationEpochMs: Long = 0L,
    val isOtgBlocked: Boolean = false
)

class ExamViewModel(
    private val examRepository: ExamRepository
) : ViewModel() {

    private val _examState = MutableStateFlow(ActiveProctoredExamState())
    val examState: StateFlow<ActiveProctoredExamState> = _examState.asStateFlow()
    private var activeExamActivityRef: WeakReference<Activity>? = null

    /**
     * Initializes a new 50-question Fisher-Yates shuffled exam session and restores any cached answers.
     */
    fun beginProctoredExamSession(
        activity: Activity?,
        exam: ExamRecord,
        studentId: String,
        studentName: String,
        questionCount: Int = 50
    ) {
        activeExamActivityRef = activity?.let { WeakReference(it) }

        // Rule 2: Enforce FLAG_SECURE screenshot & screen-recording shield
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Maha Brahmastra #4: Arm 2-second continuous C++ anti-Frida/ptrace watchdog
        runCatching { com.waqar.institute.security.NativeSecurityBridge.armHighSecurityExamWatchdog(true) }

        // Kiosk Lock Task Mode + [A1] HDMI/USB-C/Miracast/Chromecast External Display Blocker
        val kioskActive = engageAndroidKioskLockTaskMode(activity)
        var externalDisplayViolation: String? = null
        if (activity != null) {
            com.waqar.institute.security.ExamDisplayAndKioskShield.engageExamKioskAndDisplayWatchdog(
                activity = activity,
                onExternalDisplayDetected = { reason ->
                    externalDisplayViolation = reason
                    _examState.value = _examState.value.copy(
                        isMultiWindowBlocked = true,
                        securityAlertBanner = "DISPLAY SHIELD BLOCK: $reason"
                    )
                }
            )
        }

        // Rule 3: Check multi-window / split-screen mode
        val inMultiWindow = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            activity?.isInMultiWindowMode == true
        } else false) || externalDisplayViolation != null

        // Rule 4: Hardware & Root posture check + OTG & External Peripheral Check
        val posture = activity?.let { EnterpriseRaspGuard.auditDeviceSecurity(it) }
        val otgDevices = activity?.let { com.example.security.PreExamSecurityGate.detectConnectedUsbOtgOrExternalDevices(it) }.orEmpty()
        val securityWarning = when {
            externalDisplayViolation != null -> "DISPLAY SHIELD BLOCK: $externalDisplayViolation"
            otgDevices.isNotEmpty() -> "ANTI-CHEAT BLOCK: External USB OTG / Input Device detected (${otgDevices.joinToString()})! Disconnect immediately."
            inMultiWindow -> "ANTI-CHEAT BLOCK: Split-Screen / Pop-Up View detected! Exit multi-window immediately."
            posture?.isCompromised == true -> "SECURITY ALERT: Root / Magisk binary detected on device."
            else -> null
        }

        val sanitizedPaper = examRepository.prepareSanitizedPaperForExam(
            studentId = studentId,
            exam = exam
        )
        val cached = examRepository.getCachedAnswers(
            examId = exam.id,
            studentId = studentId,
            questionIds = sanitizedPaper.map { it.id }
        )

        _examState.value = ActiveProctoredExamState(
            exam = exam,
            studentId = studentId,
            studentName = studentName,
            questions = emptyList(),
            sanitizedQuestions = sanitizedPaper,
            currentQuestionIndex = 0,
            selectedAnswers = cached,
            backgroundStrikes = 0,
            focusLossWarnings = 0,
            isMultiWindowBlocked = inMultiWindow,
            isFlagSecureActive = true,
            isKioskLockTaskActive = kioskActive,
            isSubmitted = false,
            submittedResult = null,
            lastGradingOutcome = null,
            securityAlertBanner = securityWarning,
            activeSessionNonce = "",
            serverHardDeadlineAtMs = 0L
        )

        // Layer 2 — Request authoritative server exam session nonce & hard deadline
        viewModelScope.launch {
            val sessionInfo = examRepository.startAuthoritativeExamSession(
                examId = exam.id,
                durationSeconds = exam.effectiveDurationSeconds.coerceAtLeast(10)
            )
            if (_examState.value.exam?.id == exam.id && !_examState.value.isSubmitted) {
                _examState.value = _examState.value.copy(
                    activeSessionNonce = sessionInfo.sessionNonce,
                    serverHardDeadlineAtMs = sessionInfo.hardDeadlineAtMs
                )
            }
        }
    }

    /**
     * Rule 5: Records the student's option choice in memory AND caches it locally immediately.
     */
    fun selectOption(questionId: String, optionIndex: Int) {
        val current = _examState.value
        if (current.isSubmitted || current.isMultiWindowBlocked) return
        val updated = current.selectedAnswers.toMutableMap().apply {
            put(questionId, optionIndex)
        }
        val examId = current.exam?.id.orEmpty()
        examRepository.cacheAnswerLocally(examId, current.studentId, questionId, optionIndex)
        _examState.value = current.copy(selectedAnswers = updated)
    }

    fun navigateToQuestion(index: Int) {
        val current = _examState.value
        val count = maxOf(current.sanitizedQuestions.size, current.questions.size)
        if (count <= 0) return
        _examState.value = current.copy(
            currentQuestionIndex = index.coerceIn(0, count - 1)
        )
    }

    /**
     * Rule 1: Triggered when the Activity moves to the background (`ON_STOP` / Home / Recents).
     * Auto-submits the exam on reaching `maxAllowedStrikes`.
     */
    fun onAppBackgroundedOrSwitched(ownerUid: String) {
        val current = _examState.value
        if (current.isSubmitted || current.exam == null) return
        val newStrikes = current.backgroundStrikes + 1
        if (newStrikes >= current.maxAllowedStrikes) {
            _examState.value = current.copy(
                backgroundStrikes = newStrikes,
                securityAlertBanner = "ANTI-CHEAT LOCKDOWN: App switched $newStrikes times! Exam auto-submitted."
            )
            submitExamNow(
                ownerUid = ownerUid,
                violationReason = "Anti-Cheat Rule 1 Violation: App switched to background ($newStrikes strikes)"
            )
        } else {
            _examState.value = current.copy(
                backgroundStrikes = newStrikes,
                securityAlertBanner = "WARNING ($newStrikes/${current.maxAllowedStrikes}): Do NOT leave the exam screen! Next switch will auto-submit your paper."
            )
        }
    }

    /**
     * Rule 3: Triggered when floating overlays, notification shade, or split-screen steal window focus.
     */
    fun onWindowFocusOrMultiWindowChanged(hasFocus: Boolean, isMultiWindow: Boolean, ownerUid: String) {
        val current = _examState.value
        if (current.isSubmitted || current.exam == null) return
        if (isMultiWindow) {
            _examState.value = current.copy(
                isMultiWindowBlocked = true,
                securityAlertBanner = "ANTI-CHEAT RULE 3: Split-Screen / Floating Window detected! Exam locked."
            )
            submitExamNow(
                ownerUid = ownerUid,
                violationReason = "Anti-Cheat Rule 3 Violation: Split-Screen / Multi-Window detected"
            )
            return
        }
        if (!hasFocus) {
            val warnings = current.focusLossWarnings + 1
            _examState.value = current.copy(
                focusLossWarnings = warnings,
                securityAlertBanner = "FOCUS WARNING #$warnings: Floating overlay or notification drawer detected during proctored exam."
            )
        }
    }

    /**
     * Submits the exam paper (either manually by the student, automatically on timer expiry,
     * or automatically upon an Anti-Cheat rule violation) and grades via `ExamGradingGateway`.
     */
    fun submitExamNow(
        ownerUid: String,
        violationReason: String? = null,
        isPreviewOnly: Boolean = false
    ) {
        val current = _examState.value
        val exam = current.exam ?: return
        if (current.isSubmitted) return

        ActiveExamAntiCheatGuard.clearActiveExamSession(current.studentId)
        runCatching { com.waqar.institute.security.NativeSecurityBridge.armHighSecurityExamWatchdog(false) }
        disengageAndroidKioskLockTaskMode(activeExamActivityRef?.get())
        viewModelScope.launch {
            if (current.sanitizedQuestions.isNotEmpty() || current.questions.isEmpty()) {
                val outcome = examRepository.submitSanitizedExamResult(
                    exam = exam,
                    studentId = current.studentId,
                    studentName = current.studentName,
                    sanitizedQuestions = current.sanitizedQuestions,
                    selectedAnswers = current.selectedAnswers,
                    isPreviewOnly = isPreviewOnly,
                    violationReason = violationReason,
                    ownerUid = ownerUid,
                    sessionNonce = current.activeSessionNonce
                )
                _examState.value = _examState.value.copy(
                    isKioskLockTaskActive = false,
                    isSubmitted = true,
                    submittedResult = outcome.savedResult,
                    lastGradingOutcome = outcome
                )
            } else {
                val result = examRepository.submitStudentExamResult(
                    exam = exam,
                    studentId = current.studentId,
                    studentName = current.studentName,
                    questions = current.questions,
                    selectedAnswers = current.selectedAnswers,
                    violationReason = violationReason,
                    ownerUid = ownerUid
                )
                _examState.value = _examState.value.copy(
                    isKioskLockTaskActive = false,
                    isSubmitted = true,
                    submittedResult = result
                )
            }
        }
    }

    fun releaseExamSecurityShield(activity: Activity?) {
        val targetActivity = activity ?: activeExamActivityRef?.get()
        targetActivity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (targetActivity != null) {
            com.waqar.institute.security.ExamDisplayAndKioskShield.releaseExamKioskAndDisplayWatchdog(targetActivity)
        }
        disengageAndroidKioskLockTaskMode(targetActivity)
        runCatching { com.waqar.institute.security.NativeSecurityBridge.armHighSecurityExamWatchdog(false) }
        activeExamActivityRef = null
        val st = _examState.value
        ActiveExamAntiCheatGuard.clearActiveExamSession(st.studentId)
        st.exam?.id?.let { examRepository.purgeAnswerKeys(it, st.studentId) }
    }

    fun requestPeriodicBiometricReVerification(prompt: String = "Periodic Proctoring Check: Live face biometric verification required.") {
        if (!_examState.value.isSubmitted && !_examState.value.isReVerificationRequired) {
            _examState.value = _examState.value.copy(
                isReVerificationRequired = true,
                reVerificationPrompt = prompt
            )
        }
    }

    fun onBiometricReVerificationSuccess() {
        _examState.value = _examState.value.copy(
            isReVerificationRequired = false,
            reVerificationPrompt = null,
            lastReVerificationEpochMs = System.currentTimeMillis(),
            securityAlertBanner = null
        )
    }

    fun onBiometricReVerificationFailed(reason: String) {
        val current = _examState.value
        val newStrikes = current.backgroundStrikes + 1
        if (newStrikes >= current.maxAllowedStrikes) {
            submitExamNow(
                ownerUid = current.studentId,
                violationReason = "Biometric Re-verification Failed ($reason) - Exceeded Strikes ($newStrikes/${current.maxAllowedStrikes})"
            )
        } else {
            _examState.value = current.copy(
                backgroundStrikes = newStrikes,
                securityAlertBanner = "Biometric Re-verification Warning: $reason (Strike $newStrikes/${current.maxAllowedStrikes})"
            )
        }
    }

    companion object {
        /**
         * Safely activates Android Lock Task (Kiosk / Screen Pinning) Mode for the active exam Activity.
         */
        fun engageAndroidKioskLockTaskMode(activity: Activity?): Boolean {
            if (activity == null) return false
            return runCatching {
                val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val alreadyLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
                } else {
                    @Suppress("DEPRECATION")
                    am?.isInLockTaskMode == true
                }
                if (!alreadyLocked) {
                    activity.startLockTask()
                }
                true
            }.getOrDefault(false)
        }

        /**
         * Safely stops Android Lock Task (Kiosk / Screen Pinning) Mode when the exam ends or submits.
         */
        fun disengageAndroidKioskLockTaskMode(activity: Activity?) {
            if (activity == null) return
            runCatching {
                val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val isLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am != null && am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
                } else {
                    @Suppress("DEPRECATION")
                    am?.isInLockTaskMode == true
                }
                if (isLocked) {
                    activity.stopLockTask()
                }
            }
        }
    }
}
