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
                    statusMessage = "Scheduled '${exam.title}' (${exam.durationMinutes} mins, 50 Shuffled MCQs from 156-Question Pool)"
                )
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    isScheduling = false,
                    statusMessage = "Failed to schedule exam: ${err.localizedMessage}"
                )
            }
        }
    }
}
