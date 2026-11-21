package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.biometric.ExamFaceAuthViewModel
import com.example.biometric.FaceVerificationGateStatus
import com.waqar.institute.biometric.FaceBiometricVerificationEngine
import com.waqar.institute.biometric.TeacherBiometricGateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricPart2VerificationTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun examFaceAuthViewModel_blocksNullLiveSelfieEvenWhenStudentIdIsPopulated() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = ExamFaceAuthViewModel()
        var callbackFired = false

        vm.verifyStudentFaceForExam(
            context = context,
            studentId = "stu_verified_123",
            admissionPhotoDataUri = "",
            liveSelfieBitmap = null,
            onVerifiedSuccess = { callbackFired = true }
        )
        advanceUntilIdle()

        assertFalse("Exam must NOT unlock without a live camera selfie", callbackFired)
        assertTrue(
            "Status must be BlockedMismatch when liveSelfieBitmap is null",
            vm.status.value is FaceVerificationGateStatus.BlockedMismatch
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun teacherStaticProfilePhotoBypass_isRejected() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = FaceBiometricVerificationEngine.verifyTeacherWithEnrolledProfilePhoto(
            context = context,
            teacherUid = "stf_demo_1",
            teacherName = "Demo Teacher"
        )
        assertTrue(
            "Static profile photo verification must return Error and never silently pass",
            result is FaceBiometricVerificationEngine.BiometricMatchResult.Error
        )
    }

    @Test
    fun teacherBiometricGateManager_recordsAndReads24HourValidity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val teacherId = "stf_gate_test_99"
        assertFalse(TeacherBiometricGateManager.isTeacherVerifiedWithin24Hours(context, teacherId))

        TeacherBiometricGateManager.recordSuccessful24HourVerification(context, teacherId, 0.94f)
        assertTrue(TeacherBiometricGateManager.isTeacherVerifiedWithin24Hours(context, teacherId))
        assertTrue(TeacherBiometricGateManager.getRemainingGateValidityMinutes(context, teacherId) > 1400L)
    }
}
