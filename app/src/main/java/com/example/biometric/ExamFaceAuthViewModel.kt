package com.example.biometric

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Facial Biometric Exam Lock ViewModel (`ExamFaceAuthViewModel.kt`).
 *
 * Controls the pre-exam facial verification gate:
 * - Verifies a live camera frame or admission portrait via `StudentFaceEnrollmentManager`.
 * - Enforces anti-spoofing liveness and cosine similarity match (`>= 85%`).
 * - Blocks proxy exam takers with `"Biometric Face Mismatch: Only the registered student can take this exam."`
 */
sealed class FaceVerificationGateStatus {
    data object Idle : FaceVerificationGateStatus()
    data object ScanningFace : FaceVerificationGateStatus()
    data class VerifiedMatch(val similarityPercent: Int) : FaceVerificationGateStatus()
    data class BlockedMismatch(val errorMessage: String) : FaceVerificationGateStatus()
}

class ExamFaceAuthViewModel : ViewModel() {

    private val _status = MutableStateFlow<FaceVerificationGateStatus>(FaceVerificationGateStatus.Idle)
    val status: StateFlow<FaceVerificationGateStatus> = _status.asStateFlow()

    fun verifyStudentFaceForExam(
        context: Context,
        studentId: String,
        admissionPhotoDataUri: String,
        liveSelfieBitmap: Bitmap?,
        onVerifiedSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _status.value = FaceVerificationGateStatus.ScanningFace
            if (liveSelfieBitmap == null) {
                _status.value = FaceVerificationGateStatus.BlockedMismatch(
                    "Live front-camera selfie capture is required to unlock this proctored exam."
                )
                return@launch
            }

            val result = StudentFaceEnrollmentManager.verifyLiveSelfieAgainstStudent(
                context = context,
                studentId = studentId,
                admissionPhotoDataUri = admissionPhotoDataUri,
                liveSelfieBitmap = liveSelfieBitmap
            )
            result.fold(
                onSuccess = { similarity ->
                    val pct = (similarity * 100f).toInt().coerceIn(0, 100)
                    _status.value = FaceVerificationGateStatus.VerifiedMatch(similarityPercent = pct)
                    onVerifiedSuccess()
                },
                onFailure = { err ->
                    _status.value = FaceVerificationGateStatus.BlockedMismatch(
                        err.localizedMessage ?: "Biometric Face Mismatch: Only the registered student can take this exam."
                    )
                }
            )
        }
    }

    fun resetGate() {
        _status.value = FaceVerificationGateStatus.Idle
    }
}
