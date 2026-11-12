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
    val hasTimerExpiredAutoSubmit: Boolean = false
)

class StudentExamPortalViewModel : ViewModel() {

    private val _gatewayState = MutableStateFlow(StudentExamGatewayState())
    val gatewayState: StateFlow<StudentExamGatewayState> = _gatewayState.asStateFlow()

    private var countdownJob: Job? = null
    private var examTimerJob: Job? = null

    /**
     * Binds an `ExamRecord` and starts the live countdown if `examDateIso` is in the future,
     * or unlocks immediately if the scheduled start time has arrived.
     */
    fun attachExamSchedule(exam: ExamRecord, onAutoUnlock: () -> Unit = {}) {
        countdownJob?.cancel()
        val scheduledStartEpochMs = parseExamStartEpochMs(exam.scheduledDateIso)
        val totalDurationSeconds = (exam.durationMinutes.coerceAtLeast(5)) * 60

        countdownJob = viewModelScope.launch {
            while (true) {
                val nowMs = System.currentTimeMillis()
                val diffMs = scheduledStartEpochMs - nowMs
                if (diffMs > 0L) {
                    val totalSecs = diffMs / 1000L
                    val hrs = totalSecs / 3600L
                    val mins = (totalSecs % 3600L) / 60L
                    val secs = totalSecs % 60L
                    _gatewayState.value = StudentExamGatewayState(
                        exam = exam,
                        isLockedWaitingForStart = true,
                        countdownHhMmSs = "%02d:%02d:%02d".format(hrs, mins, secs),
                        isExamUnlocked = false,
                        remainingExamSeconds = totalDurationSeconds,
                        remainingFormattedMmSs = "%02d:%02d".format(totalDurationSeconds / 60, totalDurationSeconds % 60)
                    )
                    delay(1000L)
                } else {
                    _gatewayState.value = _gatewayState.value.copy(
                        exam = exam,
                        isLockedWaitingForStart = false,
                        countdownHhMmSs = "00:00:00",
                        isExamUnlocked = true,
                        remainingExamSeconds = totalDurationSeconds
                    )
                    onAutoUnlock()
                    break
                }
            }
        }
    }

    /**
     * Starts the active exam countdown timer (e.g., 60 minutes -> `00:00`) and invokes
     * `onTimeExpiredAutoSubmit()` the instant the clock reaches zero.
     */
    fun startActiveExamClock(durationMinutes: Int, onTimeExpiredAutoSubmit: () -> Unit) {
        examTimerJob?.cancel()
        val initialSeconds = (durationMinutes.coerceIn(1, 360)) * 60
        examTimerJob = viewModelScope.launch {
            var secsLeft = initialSeconds
            while (secsLeft > 0) {
                val m = secsLeft / 60
                val s = secsLeft % 60
                _gatewayState.value = _gatewayState.value.copy(
                    remainingExamSeconds = secsLeft,
                    remainingFormattedMmSs = "%02d:%02d".format(m, s),
                    hasTimerExpiredAutoSubmit = false
                )
                delay(1000L)
                secsLeft--
            }
            _gatewayState.value = _gatewayState.value.copy(
                remainingExamSeconds = 0,
                remainingFormattedMmSs = "00:00",
                hasTimerExpiredAutoSubmit = true
            )
            onTimeExpiredAutoSubmit()
        }
    }

    fun stopTimers() {
        countdownJob?.cancel()
        examTimerJob?.cancel()
    }

    private fun parseExamStartEpochMs(dateIso: String): Long {
        val clean = dateIso.trim()
        // Supports "YYYY-MM-DD HH:mm" or "YYYY-MM-DD"; if date is today or past, unlocks immediately
        return runCatching {
            if (clean.length > 10 && clean.contains(":")) {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                fmt.parse(clean.take(16))?.time ?: (System.currentTimeMillis() - 1000L)
            } else {
                System.currentTimeMillis() - 1000L
            }
        }.getOrDefault(System.currentTimeMillis() - 1000L)
    }

    override fun onCleared() {
        super.onCleared()
        stopTimers()
    }
}
