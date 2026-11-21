package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.auth.FirebaseAuthServiceProvider
import com.example.data.firestore.InstituteRepository
import com.example.data.model.BatchRecord
import com.example.data.model.PortalRole
import com.example.data.model.StudentRecord
import com.example.security.AppCheckDistributionStrategy
import com.example.security.ensureFirebaseInitialized
import com.example.security.resolveReleaseAppCheckStrategy
import com.example.viewmodel.TeacherScopeMatcher
import com.waqar.institute.biometric.FaceBiometricVerificationEngine
import com.waqar.institute.biometric.TeacherAttendanceSyncManager
import com.waqar.institute.biometric.TeacherBiometricGateManager
import com.waqar.institute.session.ConcurrentSessionManager
import com.waqar.institute.session.SessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterpriseAuditRemediationTest {

    @Test
    fun faceBiometricEngine_failsClosedOnMissingCorruptedUnavailableAndMismatch() {
        val liveVec = FloatArray(128) { i -> kotlin.math.sin((i + 1) * 0.19f) }
        val otherVec = FloatArray(128) { i -> kotlin.math.cos((i + 1) * 0.43f) }

        val missingRes = FaceBiometricVerificationEngine.evaluateLiveVectorAgainstEnrollment(
            liveEmbedding = liveVec,
            livenessScore = 0.95f,
            lookupResult = FaceBiometricVerificationEngine.EnrollmentLookupResult.Missing
        )
        assertTrue(
            "Missing enrollment must fail closed with Error, got $missingRes",
            missingRes is FaceBiometricVerificationEngine.BiometricMatchResult.Error
        )

        val unavailableRes = FaceBiometricVerificationEngine.evaluateLiveVectorAgainstEnrollment(
            liveEmbedding = liveVec,
            livenessScore = 0.95f,
            lookupResult = FaceBiometricVerificationEngine.EnrollmentLookupResult.Unavailable("Offline timeout")
        )
        assertTrue(
            "Unavailable enrollment must fail closed with Error, got $unavailableRes",
            unavailableRes is FaceBiometricVerificationEngine.BiometricMatchResult.Error
        )

        val corruptedRes = FaceBiometricVerificationEngine.evaluateLiveVectorAgainstEnrollment(
            liveEmbedding = liveVec,
            livenessScore = 0.95f,
            lookupResult = FaceBiometricVerificationEngine.EnrollmentLookupResult.Corrupted("Invalid vector")
        )
        assertTrue(
            "Corrupted enrollment must fail closed with Error, got $corruptedRes",
            corruptedRes is FaceBiometricVerificationEngine.BiometricMatchResult.Error
        )

        val mismatchRes = FaceBiometricVerificationEngine.evaluateLiveVectorAgainstEnrollment(
            liveEmbedding = liveVec,
            livenessScore = 0.95f,
            lookupResult = FaceBiometricVerificationEngine.EnrollmentLookupResult.Found(otherVec, "stf_real_1")
        )
        assertTrue(
            "Distinct face vector must return Mismatch, got $mismatchRes",
            mismatchRes is FaceBiometricVerificationEngine.BiometricMatchResult.Mismatch
        )

        val verifiedRes = FaceBiometricVerificationEngine.evaluateLiveVectorAgainstEnrollment(
            liveEmbedding = liveVec,
            livenessScore = 0.96f,
            lookupResult = FaceBiometricVerificationEngine.EnrollmentLookupResult.Found(liveVec, "stf_real_1")
        )
        assertTrue(verifiedRes is FaceBiometricVerificationEngine.BiometricMatchResult.Verified)
        assertFalse(
            "Verified result must never claim fail-open newlyEnrolled",
            (verifiedRes as FaceBiometricVerificationEngine.BiometricMatchResult.Verified).newlyEnrolled
        )
    }

    @Test
    fun faceBiometricEngine_rejectsInvalidOrPlaceholderTeacherUids() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (badUid in listOf("", " ", "teacher", "default_teacher", "cred_teacher")) {
            val lookup = FaceBiometricVerificationEngine.resolveEnrolledEmbeddingResult(
                context = context,
                teacherUid = badUid,
                teacherName = "Demo Teacher"
            )
            assertTrue(
                "Invalid teacher UID '$badUid' must be rejected as Corrupted, got $lookup",
                lookup is FaceBiometricVerificationEngine.EnrollmentLookupResult.Corrupted
            )
        }
    }

    @Test
    fun teacherBiometricGateManager_blocksPlaceholderTeacherIds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (badId in listOf("", "teacher", "default_teacher", "cred_teacher")) {
            TeacherBiometricGateManager.recordSuccessful24HourVerification(context, badId, 0.99f)
            assertFalse(
                "Placeholder teacher ID '$badId' must never become verified",
                TeacherBiometricGateManager.isTeacherVerifiedWithin24Hours(context, badId)
            )
        }
    }

    @Test
    fun teacherAttendanceSyncManager_rejectsInvalidTeacherUid() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (badId in listOf("", "teacher", "default_teacher")) {
            val res = TeacherAttendanceSyncManager.markTeacherPresentOnBiometricPass(
                context = context,
                teacherUid = badId,
                teacherName = "Test Teacher",
                cosineSimilarity = 0.95f,
                livenessConfidence = 0.95f
            )
            assertTrue(
                "Invalid teacher UID '$badId' must fail attendance sync, got $res",
                res is TeacherAttendanceSyncManager.TeacherAttendanceSyncResult.Failed
            )
        }
    }

    @Test
    fun concurrentSessionManager_returnsFalseWhenNoValidLocalSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SessionManager.clearLocalSession(context)
        ConcurrentSessionManager.stopWatchingSession()
        assertFalse(
            "Unbound/cleared session must return false from verifySubMillisecondSession",
            ConcurrentSessionManager.verifySubMillisecondSession(context, "student_test")
        )
    }

    @Test
    fun appCheckHelper_selectsDebugSideloadProviderForSideloadedApk() {
        assertEquals(
            AppCheckDistributionStrategy.PLAY_INTEGRITY_FALLBACK,
            resolveReleaseAppCheckStrategy("PLAY_INTEGRITY")
        )
        assertEquals(
            AppCheckDistributionStrategy.DEBUG_PROVIDER,
            resolveReleaseAppCheckStrategy("")
        )
        assertEquals(
            AppCheckDistributionStrategy.DEBUG_PROVIDER,
            resolveReleaseAppCheckStrategy(null)
        )
    }

    @Test
    fun teacherFirstTime128DLiveBlinkEnrollment_and_mandatoryEveryLoginVerification() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val teacherId = "stf_live_blink_teacher_01"
        val liveVec = FloatArray(128) { i -> kotlin.math.sin((i + 1) * 0.21f) }

        TeacherBiometricGateManager.clearLoginVerificationOnAuthChange()
        assertFalse(TeacherBiometricGateManager.isTeacherVerifiedForCurrentLogin(teacherId))

        val enrollRes = FaceBiometricVerificationEngine.enrollTeacherFirstTimeLiveBlinkEmbedding(
            context = context,
            teacherUid = teacherId,
            teacherName = "Prof. Salman",
            liveEmbedding = liveVec,
            livenessScore = 0.94f
        )
        assertTrue(
            "First-time 128-D Live Blink enrollment must succeed, got $enrollRes",
            enrollRes is FaceBiometricVerificationEngine.BiometricMatchResult.Verified &&
                enrollRes.newlyEnrolled
        )

        TeacherBiometricGateManager.recordSuccessfulLoginVerification(context, teacherId, 1.0f)
        assertTrue(TeacherBiometricGateManager.isTeacherVerifiedForCurrentLogin(teacherId))

        // On sign-out or next login, per-login verification resets so live blink is mandatory on every login
        TeacherBiometricGateManager.clearLoginVerificationOnAuthChange()
        assertFalse(TeacherBiometricGateManager.isTeacherVerifiedForCurrentLogin(teacherId))

        // Subsequent login finds the enrolled 128-D vector and verifies against it
        val lookup = FaceBiometricVerificationEngine.resolveEnrolledEmbeddingResult(
            context = context,
            teacherUid = teacherId,
            teacherName = "Prof. Salman"
        )
        assertTrue(lookup is FaceBiometricVerificationEngine.EnrollmentLookupResult.Found)
        val verifyRes = FaceBiometricVerificationEngine.evaluateLiveVectorAgainstEnrollment(
            liveEmbedding = liveVec,
            livenessScore = 0.92f,
            lookupResult = lookup
        )
        assertTrue(verifyRes is FaceBiometricVerificationEngine.BiometricMatchResult.Verified)
    }

    @Test
    fun teacherScopeMatcher_isolatesBatchesAndStudentsAcrossCaseWhitespaceAndBatchAdmin() {
        val batches = listOf(
            BatchRecord(
                id = "bat_adca_m",
                code = "ADCA-M",
                name = "ADCA Morning 9AM",
                courseId = "crs_adca",
                courseName = "ADCA",
                teacherId = "stf_ravi_01",
                teacherName = "Ravi Kumar"
            ),
            BatchRecord(
                id = "bat_tally_e",
                code = "TLY-E",
                name = "Tally Evening 5PM",
                courseId = "crs_tally",
                courseName = "Tally Prime",
                teacherId = "",
                teacherName = "  Dr. Ravi  Kumar  (Batch Admin) "
            ),
            BatchRecord(
                id = "bat_python_n",
                code = "PY-N",
                name = "Python Noon",
                courseId = "crs_py",
                courseName = "Python",
                teacherId = "stf_neha_02",
                teacherName = "Neha Verma"
            )
        )

        val scopedBatches = TeacherScopeMatcher.filterBatchesForTeacher(
            batches = batches,
            teacherIdKeys = setOf("stf_ravi_01", "cred_faculty_ravi"),
            teacherNameKeys = setOf("dr. ravi kumar", "faculty_ravi"),
            teacherNameSlugs = setOf("drravikumar", "ravi")
        )
        assertEquals(listOf("bat_adca_m", "bat_tally_e"), scopedBatches.map { it.id })

        val students = listOf(
            StudentRecord(id = "stu_1", fullName = "Aarav", courseName = "ADCA", batchId = "bat_adca_m", batchName = ""),
            StudentRecord(id = "stu_2", fullName = "Priya", courseName = "Tally Prime", batchId = "", batchName = " tally  evening 5pm "),
            StudentRecord(id = "stu_3", fullName = "Kabir", courseName = "ADCA", batchId = "", batchName = ""),
            StudentRecord(id = "stu_4", fullName = "Riya", courseName = "Python", batchId = "bat_python_n", batchName = "Python Noon")
        )

        val scopedStudents = TeacherScopeMatcher.filterStudentsForTeacher(
            students = students,
            teacherBatchIds = scopedBatches.map { TeacherScopeMatcher.normKey(it.id) }.toSet(),
            teacherBatchNames = scopedBatches.map { TeacherScopeMatcher.normKey(it.name) }.toSet(),
            teacherCourseNames = scopedBatches.map { TeacherScopeMatcher.normKey(it.courseName) }.toSet()
        )
        assertEquals(listOf("stu_1", "stu_2", "stu_3"), scopedStudents.map { it.id })
    }

    @Test
    fun credentialProvisioning_rejectsPasswordsShorterThanSixCharacters() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ensureFirebaseInitialized(context)
        val repo = InstituteRepository.create(context)
        val provider = FirebaseAuthServiceProvider(repo, context = context)

        val shortResult = provider.provisionOrUpdateCredential(
            username = "rohit_wci",
            rawPasswordChars = "12345".toCharArray(),
            role = PortalRole.STUDENT,
            linkedEntityId = "stu_101",
            displayName = "Rohit Verma"
        )
        assertTrue(
            "Passwords shorter than 6 characters must fail provisioning",
            shortResult.isFailure
        )

        val blankResult = provider.provisionOrUpdateCredential(
            username = "rohit_wci",
            rawPasswordChars = "".toCharArray(),
            role = PortalRole.STUDENT,
            linkedEntityId = "stu_101",
            displayName = "Rohit Verma"
        )
        assertTrue(
            "Blank passwords must never default to 123456 and must fail",
            blankResult.isFailure
        )
    }

    @Test
    fun adminInstantLocalLogin_and_failClosedWrongPassword(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ensureFirebaseInitialized(context)
        com.example.security.EnterpriseRateLimiter.resetAllForTests(context)
        val repo = InstituteRepository.create(context)
        val provider = FirebaseAuthServiceProvider(repo, context = context)

        val digest = com.example.auth.Argon2CredentialHasher.hashPasswordPbkdf2WebParity("waqar".toCharArray())
        val adminCred = com.example.data.model.PortalCredential(
            username = "waqar",
            passwordHash = digest.hashHex,
            passwordSalt = digest.saltHex,
            role = PortalRole.ADMIN.key,
            linkedEntityId = "ADMIN_ROOT",
            displayName = "Waqar Khan (Admin)",
            assignedByUid = "admin_waqar_root",
            active = true,
            isAdmin = true,
            portalRoles = listOf(PortalRole.ADMIN.key)
        )
        InstituteRepository.cacheCredentialLocally(adminCred)

        val startNs = System.nanoTime()
        val successResult = provider.signInToPortalWithCredentials(
            activityContext = context,
            role = PortalRole.ADMIN,
            username = "Waqar",
            passwordChars = "waqar".toCharArray()
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertTrue(
            "Expected AuthPortalResult.Success for cached Waqar Admin, got $successResult",
            successResult is com.example.auth.AuthPortalResult.Success
        )
        assertTrue(
            "Expected sub-250ms Robolectric login (no network wait), took ${elapsedMs}ms",
            elapsedMs < 250L
        )

        val wrongResult = provider.signInToPortalWithCredentials(
            activityContext = context,
            role = PortalRole.ADMIN,
            username = "Waqar",
            passwordChars = "wrong_pass".toCharArray()
        )
        assertTrue(
            "Wrong password must fail closed immediately",
            wrongResult is com.example.auth.AuthPortalResult.Failure
        )
        InstituteRepository.localCredentialsCache.remove("waqar")
    }

    @Test
    fun sparkPlanPortalEmailAndDerivedSecret_satisfyFirebaseAuthMinLength() {
        assertEquals(
            "waqar@portal.waqarinstitute.in",
            FirebaseAuthServiceProvider.toPortalAuthEmail(" Waqar ")
        )
        assertEquals(
            "aarav_101@portal.waqarinstitute.in",
            FirebaseAuthServiceProvider.toPortalAuthEmail("Aarav@101".replace("@", "_"))
        )
        val shortDerived = FirebaseAuthServiceProvider.derivePortalAuthSecret("waqar")
        assertEquals(64, shortDerived.length)
        assertTrue(shortDerived.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(shortDerived, FirebaseAuthServiceProvider.derivePortalAuthSecret("waqar"))
    }

    @Test
    fun enterpriseRaspGuard_detectsPcEmulatorsIncludingBlueStacksLdPlayerNoxGenymotionQemu() {
        assertTrue(
            com.example.security.EnterpriseRaspGuard.detectEmulatorMarkers(
                fingerprint = "samsung/beyond1lte/beyond1:11/RP1A/test-keys",
                model = "BlueStacks App Player",
                manufacturer = "BlueStacks",
                brand = "bluestacks",
                device = "bst",
                product = "bluestacks_cn",
                hardware = "ranchu",
                board = "unknown"
            )
        )
        assertTrue(
            com.example.security.EnterpriseRaspGuard.detectEmulatorMarkers(
                fingerprint = "ldplayer/generic/x86",
                model = "LDPlayer 9",
                manufacturer = "leidian",
                brand = "ldplayer",
                device = "ldplayer",
                product = "ldplayer",
                hardware = "x86",
                board = "goldfish"
            )
        )
        assertTrue(
            com.example.security.EnterpriseRaspGuard.detectEmulatorMarkers(
                fingerprint = "nox/bignox/nox",
                model = "SM-G977N",
                manufacturer = "samsung",
                brand = "samsung",
                device = "nox",
                product = "nox",
                hardware = "nox",
                board = "nox"
            )
        )
        assertTrue(
            com.example.security.EnterpriseRaspGuard.detectEmulatorMarkers(
                fingerprint = "genymotion/vbox86p/vbox86p:11",
                model = "Custom Phone",
                manufacturer = "Genymotion",
                brand = "generic",
                device = "vbox86p",
                product = "vbox86p",
                hardware = "vbox86",
                board = "unknown"
            )
        )
        assertTrue(
            com.example.security.EnterpriseRaspGuard.detectEmulatorMarkers(
                fingerprint = "google/sdk_gphone64_x86_64/emu64x:14",
                model = "sdk_gphone64_x86_64",
                manufacturer = "Google",
                brand = "google",
                device = "emu64x",
                product = "sdk_gphone64_x86_64",
                hardware = "ranchu",
                board = "goldfish_x86_64"
            )
        )
        assertFalse(
            "Clean physical Snapdragon phone must not be flagged as emulator",
            com.example.security.EnterpriseRaspGuard.detectEmulatorMarkers(
                fingerprint = "xiaomi/marble_in/marble:14/UKQ1.230804.001/release-keys",
                model = "23049PCD8I",
                manufacturer = "Xiaomi",
                brand = "POCO",
                device = "marble",
                product = "marble_in",
                hardware = "qcom",
                board = "ukee"
            )
        )
    }

    @Test
    fun preExamSecurityGate_blocksDeveloperOptionsAndUsbDebugging() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        android.provider.Settings.Global.putInt(
            context.contentResolver,
            android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            1
        )
        android.provider.Settings.Global.putInt(
            context.contentResolver,
            android.provider.Settings.Global.ADB_ENABLED,
            1
        )
        val blockedReport = com.example.security.PreExamSecurityGate.sanitizeAndAuditExamEnvironment(context)
        assertTrue(blockedReport.isDeveloperOptionsEnabled)
        assertTrue(blockedReport.isUsbDebuggingEnabled)
        assertTrue(blockedReport.blockingReasons.any { it.contains("Developer Options") })
        assertTrue(blockedReport.blockingReasons.any { it.contains("USB Debugging") })
        assertFalse(
            "Exam entry must remain blocked when ADB/Developer Options are active",
            blockedReport.isClearedToStartExam
        )

        android.provider.Settings.Global.putInt(
            context.contentResolver,
            android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        )
        android.provider.Settings.Global.putInt(
            context.contentResolver,
            android.provider.Settings.Global.ADB_ENABLED,
            0
        )
        val cleanReport = com.example.security.PreExamSecurityGate.sanitizeAndAuditExamEnvironment(context)
        assertFalse(cleanReport.isDeveloperOptionsEnabled)
        assertFalse(cleanReport.isUsbDebuggingEnabled)
        assertFalse(cleanReport.blockingReasons.any { it.contains("Developer Options") || it.contains("USB Debugging") })
    }

    @Test
    fun tamperProofExamClock_ignoresManualWallClockJumpsAndTracksMonotonicElapsed() {
        com.example.exam.TamperProofExamClock.resetAnchorForTests()
        val serverAnchorEpoch = 1_780_000_000_000L
        val elapsedAnchor = 50_000L
        com.example.exam.TamperProofExamClock.anchorServerEpoch(serverAnchorEpoch, elapsedAnchor)

        // Advance monotonic uptime by 60 seconds, while user manually rolls phone clock back 3 hours
        val monotonicLater = elapsedAnchor + 60_000L
        val manipulatedWallClock = serverAnchorEpoch - (3L * 3_600_000L)
        val trustedNow = com.example.exam.TamperProofExamClock.currentTrustedEpochMs(
            currentElapsedRealtimeMs = monotonicLater,
            fallbackWallClockMs = manipulatedWallClock
        )
        assertEquals(serverAnchorEpoch + 60_000L, trustedNow)
        assertTrue(
            com.example.exam.TamperProofExamClock.detectManualClockJump(
                toleranceMs = 60_000L,
                currentElapsedRealtimeMs = monotonicLater,
                wallClockMs = manipulatedWallClock
            )
        )

        val deadline = com.example.exam.TamperProofExamClock.elapsedDeadlineAfterDurationMinutes(
            durationMinutes = 30,
            currentElapsedRealtimeMs = elapsedAnchor
        )
        assertEquals(
            1800,
            com.example.exam.TamperProofExamClock.computeRemainingSeconds(deadline, elapsedAnchor)
        )
        assertEquals(
            1740,
            com.example.exam.TamperProofExamClock.computeRemainingSeconds(deadline, monotonicLater)
        )
        assertEquals(
            0,
            com.example.exam.TamperProofExamClock.computeRemainingSeconds(deadline, deadline + 5_000L)
        )
    }

    @Test
    fun sanitizedExamPaper_stripsAnswerKeysAndGradesThroughSealedVault() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ensureFirebaseInitialized(context)
        val repo = InstituteRepository.create(context)
        val examRepo = com.example.exam.ExamRepository(context, repo)

        val qJson = com.example.exam.PdfMcqBankParser.serializeCustomExamBankJson(
            questions = listOf(
                com.example.exam.ExamQuestionItem(
                    id = "SEC_Q1",
                    category = "Cybersecurity",
                    questionText = "Which flag prevents Android screenshots?",
                    options = listOf("FLAG_KEEP_SCREEN_ON", "FLAG_SECURE", "FLAG_FULLSCREEN", "FLAG_LAYOUT_NO_LIMITS"),
                    correctOptionIndex = 1,
                    marks = 10
                ),
                com.example.exam.ExamQuestionItem(
                    id = "SEC_Q2",
                    category = "ADCA",
                    questionText = "Which command invokes Kiosk pinning?",
                    options = listOf("startLockTask()", "enableEdgeToEdge()", "finishAffinity()", "requestFocus()"),
                    correctOptionIndex = 0,
                    marks = 10
                )
            ),
            questionsPerStudent = 2
        )
        val exam = com.example.data.model.ExamRecord(
            id = "exm_sec_audit_1",
            title = "Security Audit CBT",
            courseName = "Cybersecurity",
            durationMinutes = 30,
            totalMarks = 100,
            passingMarks = 40,
            questionsJson = qJson
        )

        val sanitized = examRepo.prepareSanitizedPaperForExam("stu_sec_01", exam)
        assertEquals(2, sanitized.size)

        // Identify the right option texts from sanitized options without referencing correctOptionIndex on SanitizedExamQuestion
        val ansMap = mutableMapOf<String, Int>()
        sanitized.forEach { sq ->
            val targetText = if (sq.questionText.contains("screenshots")) "FLAG_SECURE" else "startLockTask()"
            ansMap[sq.id] = sq.options.indexOf(targetText)
        }

        val outcome = examRepo.submitSanitizedExamResult(
            exam = exam,
            studentId = "stu_sec_01",
            studentName = "Audit Candidate",
            sanitizedQuestions = sanitized,
            selectedAnswers = ansMap,
            isPreviewOnly = true,
            ownerUid = "stu_sec_01"
        )
        assertEquals(2, outcome.rawCorrectCount)
        assertEquals(100, outcome.scaledMarks)
        assertTrue(outcome.passed)

        // Vault must be scrubbed after submission — re-submitting yields 0 rather than stale keys
        val replayOutcome = examRepo.submitSanitizedExamResult(
            exam = exam,
            studentId = "stu_sec_01",
            studentName = "Audit Candidate",
            sanitizedQuestions = sanitized,
            selectedAnswers = ansMap,
            isPreviewOnly = true,
            ownerUid = "stu_sec_01"
        )
        assertEquals(0, replayOutcome.rawCorrectCount)
        com.example.exam.ActiveExamAntiCheatGuard.clearActiveExamSession("stu_sec_01")
    }
}
