package com.example.exam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ExamRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Admin Exam Scheduler ViewModel (`AdminExamSchedulerViewModel.kt`).
 *
 * Allows the Admin or Teacher to schedule an upcoming Online CBT Exam with:
 * - Exam Date & Start Timestamp (`examDateIso`, e.g. `2026-11-15 02:00 PM`)
 * - Duration Window (e.g., 60 minutes)
 * - Automatic 50-question selection from the 156-question `QuestionBank` via Fisher-Yates Shuffler
 */
data class AdminExamScheduleState(
    val isScheduling: Boolean = false,
    val totalQuestionBankCount: Int = QuestionBank.fullQuestionPool.size,
    val selectedQuestionSetSize: Int = 50,
    val lastScheduledExam: ExamRecord? = null,
    val statusMessage: String? = null
)

class AdminExamSchedulerViewModel(
    private val examRepository: ExamRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AdminExamScheduleState())
    val state: StateFlow<AdminExamScheduleState> = _state.asStateFlow()

    fun scheduleTimedExam(
        code: String,
        title: String,
        courseName: String,
        batchName: String,
        examDateAndStartTimeIso: String,
        durationMinutes: Int = 60,
        totalMarks: Int = 100,
        passingMarks: Int = 40,
        ownerUid: String
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isScheduling = true, statusMessage = null)
            runCatching {
                examRepository.scheduleOnlineExam(
                    code = code,
                    title = title,
                    courseName = courseName,
                    batchName = batchName,
                    examDateIso = examDateAndStartTimeIso,
                    durationMinutes = durationMinutes,
                    totalMarks = totalMarks,
                    passingMarks = passingMarks,
                    ownerUid = ownerUid
                )
            }.onSuccess { exam ->
                _state.value = _state.value.copy(
                    isScheduling = false,
                    lastScheduledExam = exam,
                    statusMessage = "Scheduled '${exam.title}' (${exam.durationMinutes} mins) — attach PDF/MCQ bank to publish question paper"
                )
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    isScheduling = false,
                    statusMessage = "Failed to schedule exam: ${err.localizedMessage}"
                )
            }
        }
    }

    /**
     * [B1 + B2 + D3] Schedules a batch-targeted 24-hour exam supporting exact seconds (e.g. 30s) OR minutes
     * and optionally parses a ChatGPT bulk MCQ+Answer file (150+ questions), stripping answer keys
     * from student payloads and saving the canonical Master Answer Key to `/examAnswerKeys/{examId}`.
     */
    fun scheduleSmart24hBatchExam(
        code: String,
        title: String,
        courseName: String,
        batchId: String,
        batchName: String,
        dateIso: String,
        startTime24h: String,
        durationSeconds: Int,
        rawChatGptMcqText: String = "",
        createdByRole: String = "admin",
        ownerUid: String
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isScheduling = true, statusMessage = null)
            runCatching {
                val parsedMasterMcqs = if (rawChatGptMcqText.isNotBlank()) {
                    com.waqar.institute.exam.ChatGptBulkMcqFileParser.parseBulkQuestionsAndAnswers(rawChatGptMcqText)
                } else {
                    emptyList()
                }
                val combinedDateTime = "${dateIso.trim().take(10)} ${startTime24h.trim()}"
                val startEpoch = TamperProofExamClock.parse24HourScheduleEpochMs(dateIso, startTime24h)
                val safeDurationSecs = durationSeconds.coerceAtLeast(10)
                val computedMinutes = ((safeDurationSecs + 59) / 60).coerceAtLeast(1)
                val baseExam = examRepository.scheduleOnlineExam(
                    code = code,
                    title = title,
                    courseName = courseName,
                    batchName = batchName,
                    examDateIso = combinedDateTime,
                    durationMinutes = computedMinutes,
                    totalMarks = parsedMasterMcqs.size.coerceAtLeast(100),
                    passingMarks = (parsedMasterMcqs.size.coerceAtLeast(100) * 40) / 100,
                    ownerUid = ownerUid,
                    batchId = batchId,
                    startTime24h = startTime24h,
                    scheduledStartEpochMs = startEpoch,
                    durationSeconds = safeDurationSecs,
                    createdByRole = createdByRole
                )
                if (parsedMasterMcqs.isNotEmpty()) {
                    examRepository.saveMasterMcqBankAndAnswerKeyVault(
                        examId = baseExam.id,
                        examTitle = baseExam.title,
                        batchId = batchId,
                        masterQuestions = parsedMasterMcqs,
                        ownerUid = ownerUid
                    )
                }
                baseExam to parsedMasterMcqs.size
            }.onSuccess { (exam, qCount) ->
                _state.value = _state.value.copy(
                    isScheduling = false,
                    lastScheduledExam = exam,
                    selectedQuestionSetSize = if (qCount > 0) qCount else _state.value.selectedQuestionSetSize,
                    statusMessage = "Scheduled '${exam.title}' for ${exam.batchName} at ${exam.startTime24h} (${exam.effectiveDurationSeconds}s) • $qCount MCQs vaulted ✓"
                )
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    isScheduling = false,
                    statusMessage = "Failed to schedule 24h exam: ${err.localizedMessage}"
                )
            }
        }
    }
}
