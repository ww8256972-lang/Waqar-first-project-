package com.example.exam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ExamRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Student Exam Portal Gateway ViewModel (`StudentExamPortalViewModel.kt`).
 *
 * Manages:
 * - "Upcoming Exam" lock state with a live `HH:MM:SS` countdown timer until the scheduled start timestamp.
 * - Automatic exam unlock (`isExamUnlocked = true`) at the exact millisecond the countdown hits `00:00:00`.
 * - Active Exam Remaining Window Timer (`remainingExamSeconds`) that triggers automatic submission on expiry.
 */
data class StudentExamGatewayState(
    val exam: ExamRecord? = null,
    val isLockedWaitingForStart: Boolean = false,
    val countdownHhMmSs: String = "00:00:00",
    val isExamUnlocked: Boolean = true,
    val remainingExamSeconds: Int = 3600,
    val remainingFormattedMmSs: String = "60:00",
    val hasTimerExpiredAutoSubmit: Boolean = false,
    val activeSessionNonce: String = "",
    val serverHardDeadlineAtMs: Long = 0L,
    val isAuthoritativeServerSession: Boolean = false
)

class StudentExamPortalViewModel : ViewModel() {

    private val _gatewayState = MutableStateFlow(StudentExamGatewayState())
    val gatewayState: StateFlow<StudentExamGatewayState> = _gatewayState.asStateFlow()

    private var countdownJob: Job? = null
    private var examTimerJob: Job? = null

    /**
     * Binds an `ExamRecord` and starts the live countdown if `scheduledStartEpochMs` / `scheduledDateIso` + `startTime24h`
     * is in the future, or unlocks immediately if the scheduled start time has arrived.
     */
    fun attachExamSchedule(exam: ExamRecord, onAutoUnlock: () -> Unit = {}) {
        countdownJob?.cancel()
        val scheduledStartEpochMs = resolveExamStartEpochMs(exam)
        val totalDurationSeconds = exam.effectiveDurationSeconds.coerceAtLeast(10)

        countdownJob = viewModelScope.launch {
            launch { TamperProofExamClock.syncServerTimeOffsetAsync() }
            while (true) {
                val nowMs = TamperProofExamClock.currentTrustedEpochMs()
                val diffMs = scheduledStartEpochMs - nowMs
                if (diffMs > 0L) {
                    val totalSecs = (diffMs + 999L) / 1000L
                    _gatewayState.value = StudentExamGatewayState(
                        exam = exam,
                        isLockedWaitingForStart = true,
                        countdownHhMmSs = TamperProofExamClock.formatHhMmSs(totalSecs),
                        isExamUnlocked = false,
                        remainingExamSeconds = totalDurationSeconds,
                        remainingFormattedMmSs = TamperProofExamClock.formatMmSs(totalDurationSeconds)
                    )
                    delay(500L)
                } else {
                    _gatewayState.value = _gatewayState.value.copy(
                        exam = exam,
                        isLockedWaitingForStart = false,
                        countdownHhMmSs = "00:00:00",
                        isExamUnlocked = true,
                        remainingExamSeconds = totalDurationSeconds,
                        remainingFormattedMmSs = TamperProofExamClock.formatMmSs(totalDurationSeconds)
                    )
                    onAutoUnlock()
                    break
                }
            }
        }
    }

    /**
     * Starts the active exam countdown timer using monotonic `SystemClock.elapsedRealtime()` so
     * manual clock changes or coroutine sleep cannot extend exam duration.
     */
    fun startActiveExamClock(durationMinutes: Int, onTimeExpiredAutoSubmit: () -> Unit) {
        startActiveExamClockSeconds(durationMinutes.coerceAtLeast(1) * 60, onTimeExpiredAutoSubmit)
    }

    /**
     * [B1] Exact-second active exam timer supporting sub-minute exams (e.g. 30s) as well as multi-minute exams.
     */
    fun startActiveExamClockSeconds(durationSeconds: Int, onTimeExpiredAutoSubmit: () -> Unit) {
        examTimerJob?.cancel()
        val deadlineElapsedMs = TamperProofExamClock.elapsedDeadlineAfterDurationSeconds(durationSeconds)
        examTimerJob = viewModelScope.launch {
            while (true) {
                val secsLeft = TamperProofExamClock.computeRemainingSeconds(deadlineElapsedMs)
                if (secsLeft <= 0) {
                    break
                }
                _gatewayState.value = _gatewayState.value.copy(
                    remainingExamSeconds = secsLeft,
                    remainingFormattedMmSs = TamperProofExamClock.formatMmSs(secsLeft),
                    hasTimerExpiredAutoSubmit = false
                )
                delay(250L)
            }
            _gatewayState.value = _gatewayState.value.copy(
                remainingExamSeconds = 0,
                remainingFormattedMmSs = "00:00",
                hasTimerExpiredAutoSubmit = true
            )
            onTimeExpiredAutoSubmit()
        }
    }

    /**
     * Layer 2 — Binds an authoritative server-issued `ServerExamSessionInfo` (`sessionNonce` +
     * `hardDeadlineAtMs`) and locks the active countdown timer directly to the server's deadline.
     */
    fun bindServerExamSession(
        sessionInfo: ServerExamSessionInfo,
        onTimeExpiredAutoSubmit: () -> Unit
    ) {
        examTimerJob?.cancel()
        val deadlineElapsedMs = TamperProofExamClock.anchorAuthoritativeServerDeadline(
            serverNowMs = sessionInfo.serverNowMs,
            hardDeadlineAtMs = sessionInfo.hardDeadlineAtMs
        )
        _gatewayState.value = _gatewayState.value.copy(
            activeSessionNonce = sessionInfo.sessionNonce,
            serverHardDeadlineAtMs = sessionInfo.hardDeadlineAtMs,
            isAuthoritativeServerSession = sessionInfo.isAuthoritativeServerSession
        )
        examTimerJob = viewModelScope.launch {
            while (true) {
                val secsLeft = TamperProofExamClock.computeRemainingSeconds(deadlineElapsedMs)
                if (secsLeft <= 0) {
                    break
                }
                _gatewayState.value = _gatewayState.value.copy(
                    remainingExamSeconds = secsLeft,
                    remainingFormattedMmSs = TamperProofExamClock.formatMmSs(secsLeft),
                    hasTimerExpiredAutoSubmit = false
                )
                delay(250L)
            }
            _gatewayState.value = _gatewayState.value.copy(
                remainingExamSeconds = 0,
                remainingFormattedMmSs = "00:00",
                hasTimerExpiredAutoSubmit = true
            )
            onTimeExpiredAutoSubmit()
        }
    }

    /**
     * [B3] Builds a unique deterministic per-student shuffled paper (`HMAC-SHA256(examId|studentId)`).
     */
    fun buildPerStudentShuffledPaper(
        examId: String,
        studentId: String,
        masterQuestions: List<com.waqar.institute.exam.MasterMcqQuestion>
    ): List<com.waqar.institute.exam.StudentSafeMcqQuestion> =
        com.waqar.institute.exam.PerStudentExamShuffleEngine.buildStudentShuffledPaper(
            examId = examId,
            studentId = studentId,
            masterQuestions = masterQuestions
        )

    /**
     * [B3] Grades canonical student selections with exact percentage, grade, and pass/fail status.
     */
    fun gradeShuffledExamSubmission(
        masterQuestions: List<com.waqar.institute.exam.MasterMcqQuestion>,
        selectedOriginalOptionByMasterQId: Map<String, Int>,
        marksPerQuestion: Int = 1,
        passingPercentage: Double = 40.0
    ): com.waqar.institute.exam.ExactExamGradingSummary =
        com.waqar.institute.exam.PerStudentExamShuffleEngine.gradeSubmissionWithExactPercentage(
            masterQuestions = masterQuestions,
            selectedOriginalOptionByMasterQId = selectedOriginalOptionByMasterQId,
            marksPerQuestion = marksPerQuestion,
            passingPercentage = passingPercentage
        )

    fun stopTimers() {
        countdownJob?.cancel()
        examTimerJob?.cancel()
    }

    private fun resolveExamStartEpochMs(exam: ExamRecord): Long {
        if (exam.scheduledStartEpochMs > 0L) return exam.scheduledStartEpochMs
        val parsed24h = TamperProofExamClock.parse24HourScheduleEpochMs(exam.scheduledDateIso, exam.startTime24h)
        if (parsed24h > 0L) return parsed24h
        return parseExamStartEpochMs(exam.scheduledDateIso)
    }

    private fun parseExamStartEpochMs(dateIso: String): Long {
        val clean = dateIso.trim()
        val trustedNow = TamperProofExamClock.currentTrustedEpochMs()
        return runCatching {
            if (clean.length > 10 && clean.contains(":")) {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                fmt.parse(clean.take(16))?.time ?: (trustedNow - 1000L)
            } else {
                trustedNow - 1000L
            }
        }.getOrDefault(trustedNow - 1000L)
    }

    override fun onCleared() {
        super.onCleared()
        stopTimers()
    }
}
