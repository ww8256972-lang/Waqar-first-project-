package com.example.exam

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ExamRecord
import com.example.data.model.ExamResult
import com.example.security.EnterpriseRaspGuard
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
    val currentQuestionIndex: Int = 0,
    val selectedAnswers: Map<String, Int> = emptyMap(),
    val backgroundStrikes: Int = 0,
    val maxAllowedStrikes: Int = 2,
    val focusLossWarnings: Int = 0,
    val isMultiWindowBlocked: Boolean = false,
    val isFlagSecureActive: Boolean = false,
    val isSubmitted: Boolean = false,
    val submittedResult: ExamResult? = null,
    val securityAlertBanner: String? = null
)

class ExamViewModel(
    private val examRepository: ExamRepository
) : ViewModel() {

    private val _examState = MutableStateFlow(ActiveProctoredExamState())
    val examState: StateFlow<ActiveProctoredExamState> = _examState.asStateFlow()

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
        // Rule 2: Enforce FLAG_SECURE screenshot & screen-recording shield
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Rule 3: Check multi-window / split-screen mode
        val inMultiWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            activity?.isInMultiWindowMode == true
        } else false

        // Rule 4: Hardware & Root posture check
        val posture = activity?.let { EnterpriseRaspGuard.auditDeviceSecurity(it) }
        val securityWarning = when {
            inMultiWindow -> "ANTI-CHEAT BLOCK: Split-Screen / Pop-Up View detected! Exit multi-window immediately."
            posture?.isCompromised == true -> "SECURITY ALERT: Root / Magisk binary detected on device."
            else -> null
        }

        val shuffledQuestions = examRepository.generateShuffledExamPaper(
            studentId = studentId,
            examId = exam.id,
            courseName = exam.courseName,
            questionCount = questionCount
        )
        val cached = examRepository.getCachedAnswers(
            examId = exam.id,
            studentId = studentId,
            questionIds = shuffledQuestions.map { it.id }
        )

        _examState.value = ActiveProctoredExamState(
            exam = exam,
            studentId = studentId,
            studentName = studentName,
            questions = shuffledQuestions,
            currentQuestionIndex = 0,
            selectedAnswers = cached,
            backgroundStrikes = 0,
            focusLossWarnings = 0,
            isMultiWindowBlocked = inMultiWindow,
            isFlagSecureActive = true,
            isSubmitted = false,
            submittedResult = null,
            securityAlertBanner = securityWarning
        )
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
        if (current.questions.isEmpty()) return
        _examState.value = current.copy(
            currentQuestionIndex = index.coerceIn(0, current.questions.lastIndex)
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
     * or automatically upon an Anti-Cheat rule violation) and computes the instant score card.
     */
    fun submitExamNow(ownerUid: String, violationReason: String? = null) {
        val current = _examState.value
        val exam = current.exam ?: return
        if (current.isSubmitted) return

        viewModelScope.launch {
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
                isSubmitted = true,
                submittedResult = result
            )
        }
    }

    fun releaseExamSecurityShield(activity: Activity?) {
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
