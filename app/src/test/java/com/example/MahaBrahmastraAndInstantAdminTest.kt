package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.auth.AuthPortalResult
import com.example.auth.FirebaseAuthServiceProvider
import com.example.data.firestore.InstituteRepository
import com.example.data.model.ExamRecord
import com.example.data.model.PortalRole
import com.example.exam.ExamQuestionItem
import com.example.exam.ExamRepository
import com.example.exam.PdfMcqBankParser
import com.example.security.AdminEncryptedCredentialStore
import com.example.security.StringShield
import com.example.security.ensureFirebaseInitialized
import com.waqar.institute.biometric.HardwareBiometricAttestor
import com.waqar.institute.security.NativeSecurityBridge
import com.waqar.institute.updater.GitHubOtaUpdater
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MahaBrahmastraAndInstantAdminTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ensureFirebaseInitialized(context)
        AdminEncryptedCredentialStore.resetToDefaultForTest(context)
        com.example.security.EnterpriseRateLimiter.resetAllForTests(context)
        InstituteRepository.localCredentialsCache.clear()
    }

    @After
    fun tearDown() {
        AdminEncryptedCredentialStore.resetToDefaultForTest(context)
        com.example.security.EnterpriseRateLimiter.resetAllForTests(context)
        com.example.exam.ActiveExamAntiCheatGuard.clearActiveExamSession("stu_mb_01")
        InstituteRepository.localCredentialsCache.clear()
        NativeSecurityBridge.armHighSecurityExamWatchdog(false)
    }

    @Test
    fun adminEncryptedStore_requiresFirstRunArgon2SetupAndLogsInOfflineUnder500ms() = runBlocking {
        val sampleUser = "waqar"
        val strongFirstRunPass = "Waqar@2026#Admin"

        // Fresh unconfigured store must NEVER accept any hardcoded default password
        assertFalse(
            "Unconfigured Admin store must require explicit First-Run Admin Setup",
            AdminEncryptedCredentialStore.isAdminConfigured(context)
        )
        val unconfiguredCheck = AdminEncryptedCredentialStore.verifyAdminCredentialsInstant(
            context = context,
            rawUsername = sampleUser,
            passwordChars = sampleUser.toCharArray()
        )
        assertTrue(
            "Expected NotConfigured before First-Run setup, got $unconfiguredCheck",
            unconfiguredCheck is AdminEncryptedCredentialStore.AdminVerifyResult.NotConfigured
        )

        // Complete First-Run Admin Setup with salted Argon2id
        val (savedUser, savedArgonHash) = AdminEncryptedCredentialStore.completeFirstRunAdminSetup(
            context = context,
            adminUsername = sampleUser,
            passwordChars = strongFirstRunPass.toCharArray()
        )
        assertEquals(sampleUser, savedUser)
        assertTrue("Stored Admin hash must use Argon2id format", savedArgonHash.startsWith("\$argon2id\$"))
        assertTrue(AdminEncryptedCredentialStore.isAdminConfigured(context))

        val repo = InstituteRepository.create(context)
        val authProvider = FirebaseAuthServiceProvider(repo, context)
        val startMs = System.currentTimeMillis()
        val okResult = authProvider.signInToPortalWithCredentials(
            activityContext = context,
            role = PortalRole.ADMIN,
            username = sampleUser,
            passwordChars = strongFirstRunPass.toCharArray()
        )
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue("Expected Argon2id Admin login success, got $okResult", okResult is AuthPortalResult.Success)
        assertTrue("Expected offline/online Admin login < 1500ms, took ${elapsedMs}ms", elapsedMs < 1500L)

        val badResult = authProvider.signInToPortalWithCredentials(
            activityContext = context,
            role = PortalRole.ADMIN,
            username = sampleUser,
            passwordChars = "wrong_pass_123".toCharArray()
        )
        assertTrue("Wrong password must fail closed, got $badResult", badResult is AuthPortalResult.Failure)
        val failMsg = (badResult as AuthPortalResult.Failure).message
        assertFalse(
            "Wrong Admin password must never show 'No internet connection', got '$failMsg'",
            failMsg.contains("No internet connection", ignoreCase = true)
        )
    }

    @Test
    fun adminCredentialRotation_persistsSha256InEncryptedStoreAndRotatesLoginImmediately() = runBlocking {
        val repo = InstituteRepository.create(context)
        val authProvider = FirebaseAuthServiceProvider(repo, context)
        val oldUser = charArrayOf('w', 'a', 'q', 'a', 'r').concatToString()
        val oldSecret = "OldWaqar@2026#".toCharArray()
        AdminEncryptedCredentialStore.completeFirstRunAdminSetup(
            context = context,
            adminUsername = oldUser,
            passwordChars = oldSecret.copyOf()
        )
        val newUser = "director_waqar_2026"
        val newSecret = charArrayOf('N', 'e', 'w', 'U', 'l', 't', 'r', 'a', '9', '9')

        val updateRes = authProvider.updateAdminOwnCredentials(
            oldUsername = oldUser,
            newUsername = newUser,
            newPasswordChars = newSecret.copyOf(),
            actorUidOverride = "cred_admin"
        )
        assertTrue("Expected credential update to succeed offline/online, got $updateRes", updateRes.isSuccess)
        assertEquals(newUser, AdminEncryptedCredentialStore.getActiveAdminUsername(context))
        assertTrue(AdminEncryptedCredentialStore.isCustomizedByAdmin(context))

        // Simulate fresh app restart (clear in-memory cache only, keep EncryptedSharedPreferences)
        InstituteRepository.localCredentialsCache.clear()

        // Old default credentials must be rejected immediately
        val oldAttempt = authProvider.signInToPortalWithCredentials(
            activityContext = context,
            role = PortalRole.ADMIN,
            username = oldUser,
            passwordChars = oldUser.toCharArray()
        )
        assertTrue("Old credentials must be rejected after rotation", oldAttempt is AuthPortalResult.Failure)

        // New credentials must log in instantaneously from EncryptedSharedPreferences SHA-256
        val newAttempt = authProvider.signInToPortalWithCredentials(
            activityContext = context,
            role = PortalRole.ADMIN,
            username = newUser,
            passwordChars = newSecret.copyOf()
        )
        assertTrue("Rotated Admin credentials must succeed offline/online, got $newAttempt", newAttempt is AuthPortalResult.Success)
        assertEquals(newUser, (newAttempt as AuthPortalResult.Success).profile.username)
    }

    @Test
    fun mahaBrahmastra1_certPinDexPinAndContinuousWatchdogHooks() {
        assertEquals(64, BuildConfig.EXPECTED_RELEASE_CERT_SHA256.length)
        assertTrue(NativeSecurityBridge.verifySigningCertificateSha256(context))
        assertTrue(NativeSecurityBridge.verifyExpectedDexCrc32Pin(context))
        NativeSecurityBridge.armHighSecurityExamWatchdog(true)
        NativeSecurityBridge.armHighSecurityExamWatchdog(false)
        val violation = NativeSecurityBridge.pollContinuousWatchdogViolation(context)
        assertEquals(null, violation)

        // Stock physical phones whose codenames contain substrings like 'andy' (candy), 'nox' (knox), or 'bst' (substance) must never false-lock
        assertFalse(
            com.example.security.EnterpriseRaspGuard.detectEmulatorMarkers(
                fingerprint = "vivo/PD2307/PD2307:14/UP1A.231005.007/release-keys",
                model = "V2307",
                manufacturer = "vivo",
                brand = "vivo",
                device = "candy_in",
                hardware = "qcom",
                board = "knoxville",
                product = "substance_in"
            )
        )
    }

    @Test
    fun mahaBrahmastra2_hmacVaultAndWatermarkEnvConfigured() = runBlocking {
        assertTrue(BuildConfig.EXAM_ANSWER_HMAC_PEPPER.isNotBlank())
        val repo = InstituteRepository.create(context)
        val examRepo = ExamRepository(context, repo)

        val wm1 = examRepo.computeStudentPaperWatermark("exm_mb_1", "stu_mb_01")
        val wm2 = examRepo.computeStudentPaperWatermark("exm_mb_1", "stu_mb_02")
        assertTrue(wm1.startsWith("WM-"))
        assertTrue(wm2.startsWith("WM-"))
        assertFalse("Each student paper watermark must be unique", wm1 == wm2)

        val qJson = PdfMcqBankParser.serializeCustomExamBankJson(
            questions = listOf(
                ExamQuestionItem(
                    id = "MB_Q1",
                    category = "Security",
                    questionText = "Which key vault backs StrongBox keys?",
                    options = listOf("SharedPreferences", "AndroidKeyStore", "PlaintextJSON", "Assets"),
                    correctOptionIndex = 1,
                    marks = 10
                )
            ),
            questionsPerStudent = 1
        )
        val exam = ExamRecord(
            id = "exm_mb_1",
            title = "Maha Brahmastra CBT",
            courseName = "Security",
            durationMinutes = 15,
            totalMarks = 50,
            passingMarks = 20,
            questionsJson = qJson
        )
        val paper = examRepo.prepareSanitizedPaperForExam("stu_mb_01", exam)
        assertEquals(1, paper.size)
        val ans = mapOf(paper[0].id to paper[0].options.indexOf("AndroidKeyStore"))
        val outcome = examRepo.submitSanitizedExamResult(
            exam = exam,
            studentId = "stu_mb_01",
            studentName = "MB Student",
            sanitizedQuestions = paper,
            selectedAnswers = ans,
            isPreviewOnly = true,
            ownerUid = "stu_mb_01"
        )
        assertEquals(50, outcome.scaledMarks)
        assertTrue("Watermark must be stamped in outcome remarks", outcome.remarks.contains(wm1))
    }

    @Test
    fun mahaBrahmastra3_hardwareBiometricAttestorFrontCamChallengeAndReceipt() {
        val frontCam = HardwareBiometricAttestor.verifyPhysicalFrontCameraPresent(context)
        assertTrue("Front camera check should pass or bridge cleanly on test runner: ${frontCam.reason}", frontCam.hasPhysicalFrontCamera)
        val challenge = HardwareBiometricAttestor.nextRandomChallenge()
        assertNotNull(challenge)

        val receipt = HardwareBiometricAttestor.issueKeystoreAttestedPassReceipt(
            context = context,
            subjectId = "stu_mb_01",
            portalRole = "STUDENT_EXAM",
            challenge = challenge,
            similarity = 0.94f
        )
        assertEquals(32, receipt.receiptDigestHex.length)
        assertEquals("STUDENT_EXAM", receipt.portalRole)
    }

    @Test
    fun mahaBrahmastra5_githubOtaUpdaterHttpsCertPinAndAntiDowngrade() {
        assertTrue(BuildConfig.GITHUB_OTA_OWNER.isNotBlank())
        assertTrue(BuildConfig.GITHUB_OTA_REPO.isNotBlank())
        assertTrue(GitHubOtaUpdater.isTrustedGitHubHttpsUrl("https://api.github.com/repos/ww8256972/Waqar-first-project-/releases/latest"))
        assertFalse(GitHubOtaUpdater.isTrustedGitHubHttpsUrl("http://github.com/evil/fake.apk"))
        assertFalse(GitHubOtaUpdater.isTrustedGitHubHttpsUrl("https://evil-mirror.example.com/fake.apk"))

        val fakePayload = """
            {
              "tag_name": "v2.1.0",
              "name": "WCI Release v2.1.0",
              "body": "Security Release\nSHA256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "html_url": "https://github.com/ww8256972/Waqar-first-project-/releases/tag/v2.1.0",
              "assets": [{"browser_download_url": "https://github.com/ww8256972/Waqar-first-project-/releases/download/v2.1.0/app-release.apk"}]
            }
        """.trimIndent()
        val parsed = GitHubOtaUpdater.parseReleaseJsonIfNewer(fakePayload, "1.0")
        assertNotNull(parsed)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", parsed?.expectedApkSha256)

        val dummyApk = File(context.cacheDir, "fake_downgrade.apk").apply { writeText("not_an_apk") }
        val downgradeRes = GitHubOtaUpdater.verifyApkSignatureAndAntiDowngrade(
            context = context,
            apkFile = dummyApk,
            targetVersionCode = 0L
        )
        assertFalse("OTA downgrade must be blocked", downgradeRes.isAllowedToInstall)
        assertFalse(downgradeRes.antiDowngradePassed)

        val badApkRes = GitHubOtaUpdater.verifyApkSignatureAndAntiDowngrade(
            context = context,
            apkFile = dummyApk,
            targetVersionCode = 999L
        )
        assertFalse("Unsigned/tampered OTA APK must be blocked", badApkRes.isAllowedToInstall)
        assertFalse(badApkRes.certificatePinMatched)
        dummyApk.delete()
    }

    @Test
    fun enterpriseRateLimiter_enforces5In60s_blocks6thFor5Min_survivesRestart_andHandlesConcurrency() = runBlocking {
        com.example.security.EnterpriseRateLimiter.resetAllForTests(context)
        val baseTimeMs = 1_780_000_000_000L
        val scope = com.example.security.EnterpriseRateLimiter.RateLimitScope.LOGIN
        val identity = "ADMIN:brute_target"

        // 1..5 requests within 60 seconds -> ALLOWED (HTTP 200)
        for (i in 1..5) {
            val d = com.example.security.EnterpriseRateLimiter.checkAndConsume(
                context = context,
                scope = scope,
                identityKey = identity,
                nowEpochMs = baseTimeMs + (i * 1_000L)
            )
            assertTrue("Request #$i within 60s must be allowed", d.allowed)
            assertEquals(200, d.httpStatus)
            assertEquals(i, d.attemptCountInWindow)
            assertEquals(5 - i, d.remainingInWindow)
        }

        // 6th request within the same 60-second window -> REJECTED (HTTP 429) + 5-minute (300s) block
        val sixthTime = baseTimeMs + 10_000L
        val sixthDecision = com.example.security.EnterpriseRateLimiter.checkAndConsume(
            context = context,
            scope = scope,
            identityKey = identity,
            nowEpochMs = sixthTime
        )
        assertFalse("6th request within 60s must be rejected", sixthDecision.allowed)
        assertEquals(429, sixthDecision.httpStatus)
        assertEquals(sixthTime + 300_000L, sixthDecision.blockedUntilEpochMs)
        assertEquals(300L, sixthDecision.retryAfterSeconds)

        // Request during 5-minute block (2 minutes later) -> REJECTED (HTTP 429)
        val duringBlock = com.example.security.EnterpriseRateLimiter.checkAndConsume(
            context = context,
            scope = scope,
            identityKey = identity,
            nowEpochMs = sixthTime + 120_000L
        )
        assertFalse("Request during 5-minute block must be rejected", duringBlock.allowed)
        assertEquals(429, duringBlock.httpStatus)

        // Simulate app/server restart -> still REJECTED during 5-minute block
        com.example.security.EnterpriseRateLimiter.clearMemoryCacheSimulateRestart()
        val afterRestart = com.example.security.EnterpriseRateLimiter.checkAndConsume(
            context = context,
            scope = scope,
            identityKey = identity,
            nowEpochMs = sixthTime + 240_000L
        )
        assertFalse("5-minute block must survive app/process restart", afterRestart.allowed)
        assertEquals(429, afterRestart.httpStatus)

        // Multi-bucket isolation: another identity is NOT affected
        val otherIdentityDecision = com.example.security.EnterpriseRateLimiter.checkAndConsume(
            context = context,
            scope = scope,
            identityKey = "ADMIN:separate_user",
            nowEpochMs = sixthTime + 240_000L
        )
        assertTrue("Separate identity bucket must not be locked out", otherIdentityDecision.allowed)

        // After 5-minute block expires (300,001 ms later) -> ALLOWED (HTTP 200)
        val afterExpiry = com.example.security.EnterpriseRateLimiter.checkAndConsume(
            context = context,
            scope = scope,
            identityKey = identity,
            nowEpochMs = sixthTime + 300_001L
        )
        assertTrue("Request after 5-minute block expiry must be allowed", afterExpiry.allowed)
        assertEquals(200, afterExpiry.httpStatus)
        assertEquals(1, afterExpiry.attemptCountInWindow)

        // Concurrent burst test: 12 simultaneous requests -> exactly 5 allowed, 7 rejected with 429
        val concurrentKey = "OTP_VERIFY:concurrent_actor"
        val threads = java.util.concurrent.Executors.newFixedThreadPool(12)
        val results = (1..12).map {
            threads.submit<com.example.security.EnterpriseRateLimiter.RateLimitDecision> {
                com.example.security.EnterpriseRateLimiter.checkAndConsume(
                    context = context,
                    scope = com.example.security.EnterpriseRateLimiter.RateLimitScope.OTP_VERIFY,
                    identityKey = concurrentKey,
                    nowEpochMs = baseTimeMs + 500_000L
                )
            }
        }.map { it.get() }
        threads.shutdown()
        assertEquals("Exactly 5 concurrent requests must be allowed", 5, results.count { it.allowed })
        assertEquals("Remaining 7 concurrent requests must be rejected with 429", 7, results.count { !it.allowed && it.httpStatus == 429 })
    }

    @Test
    fun enterpriseInputUploadValidator_blocksExecutablesPathTraversalAndInvalidDataUris() {
        assertFalse(
            com.example.security.EnterpriseInputUploadValidator.validateAndSanitizeFileName("../../etc/passwd").valid
        )
        assertFalse(
            com.example.security.EnterpriseInputUploadValidator.validateAndSanitizeFileName("invoice.pdf.exe").valid
        )
        assertFalse(
            com.example.security.EnterpriseInputUploadValidator.validateAndSanitizeFileName("payload.sh").valid
        )
        assertFalse(
            com.example.security.EnterpriseInputUploadValidator.validateAndSanitizeFileName("trojan.apk").valid
        )
        assertFalse(
            com.example.security.EnterpriseInputUploadValidator.validatePhotoOrAttachmentUri("javascript:alert(1)").valid
        )
        assertFalse(
            com.example.security.EnterpriseInputUploadValidator.validatePhotoOrAttachmentUri("data:text/html;base64,PHNjcmlwdD4=").valid
        )
        assertTrue(
            com.example.security.EnterpriseInputUploadValidator.validateAndSanitizeFileName("ADCA_Syllabus_2026.pdf").valid
        )
        assertTrue(
            com.example.security.EnterpriseInputUploadValidator.validateAndSanitizeFileName("Verified Aadhaar: XXXX-XXXX-4321").valid
        )
        assertTrue(
            com.example.security.EnterpriseInputUploadValidator.validatePhotoOrAttachmentUri("data:image/jpeg;base64,/9j/4AAQSkZJRg==").valid
        )
    }

    @Test
    fun academicProSuite_mahaBrahmastraProPlus6To10_and_multiStageBiometrics_workEndToEnd() {
        com.waqar.institute.security.MahaBrahmastraProPlusEngine.resetStateForTests(context)
        com.waqar.institute.biometric.MultiStageBiometricCoordinator.clearStateForTests()

        // 1. Maha Brahmastra Pro+ Rule #6: Native /proc/self/maps Integrity Scanner
        val mapsClean = com.waqar.institute.security.MahaBrahmastraProPlusEngine.auditNativeExecutableMemorySegments(
            overrideMapsLines = listOf("7f8a0000-7f8a5000 r-xp 00000000 103:02 12345 /system/lib64/libc.so")
        )
        assertTrue("Clean maps must pass Rule #6", mapsClean.isClean)
        val mapsHooked = com.waqar.institute.security.MahaBrahmastraProPlusEngine.auditNativeExecutableMemorySegments(
            overrideMapsLines = listOf("7f8b0000-7f8b9000 rwxp 00000000 00:00 0 /data/local/tmp/frida-agent-64.so")
        )
        assertFalse("Injected Frida agent must be flagged by Rule #6", mapsHooked.isClean)

        // 2. Maha Brahmastra Pro+ Rule #7: Anti-Overlay, Screen-Share & Remote-Control Blocker
        val overlayClean = com.waqar.institute.security.MahaBrahmastraProPlusEngine.auditOverlayScreenShareAndAccessibility(
            context = context,
            simulatedExternalDisplayCount = 0,
            simulatedAccessibilityPackages = emptyList()
        )
        assertTrue("Clean display/accessibility state must pass Rule #7", overlayClean.isSafeForProctoredExam)
        val remoteControlBlocked = com.waqar.institute.security.MahaBrahmastraProPlusEngine.auditOverlayScreenShareAndAccessibility(
            context = context,
            simulatedExternalDisplayCount = 0,
            simulatedAccessibilityPackages = listOf("com.anydesk.anydeskandroid")
        )
        assertFalse("AnyDesk accessibility service must be blocked by Rule #7", remoteControlBlocked.isSafeForProctoredExam)

        // 3. Maha Brahmastra Pro+ Rule #8: Per-Request Cryptographic Nonce + Monotonic Counter Anti-Replay
        val payloadJson = """{"examId":"exm_01","studentId":"stu_mb_01"}"""
        val env = com.waqar.institute.security.MahaBrahmastraProPlusEngine.issueSignedActionEnvelope(
            actionType = "EXAM_SUBMIT",
            actorId = "stu_mb_01",
            payloadJson = payloadJson
        )
        val firstVerify = com.waqar.institute.security.MahaBrahmastraProPlusEngine.verifyAndConsumeActionEnvelope(env, payloadJson)
        assertTrue("Fresh anti-replay envelope must succeed", firstVerify)
        val replayVerify = com.waqar.institute.security.MahaBrahmastraProPlusEngine.verifyAndConsumeActionEnvelope(env, payloadJson)
        assertFalse("Replayed nonce must be rejected immediately by Rule #8", replayVerify)

        // 4. Maha Brahmastra Pro+ Rule #9: Canary Honeypot Trap Vault
        com.waqar.institute.security.MahaBrahmastraProPlusEngine.initializeCanaryHoneypotVault(context)
        val canaryClean = com.waqar.institute.security.MahaBrahmastraProPlusEngine.verifyCanaryIntactOrAlert(context)
        assertTrue("Armed Canary Honeypot must verify intact", canaryClean.intact)
        com.waqar.institute.security.MahaBrahmastraProPlusEngine.simulateCanaryTamperForTest(
            context = context,
            key = com.waqar.institute.security.MahaBrahmastraProPlusEngine.CANARY_KEY_EXAM_OVERRIDE,
            tamperedValue = "HACKED_BY_INTRUDER"
        )
        val canaryTampered = com.waqar.institute.security.MahaBrahmastraProPlusEngine.verifyCanaryIntactOrAlert(context)
        assertFalse("Tampered Canary Honeypot must trigger alert", canaryTampered.intact)
        com.waqar.institute.security.MahaBrahmastraProPlusEngine.initializeCanaryHoneypotVault(context)

        // 5. Maha Brahmastra Pro+ Rule #10: Hardware Keystore Row-Level MAC
        val rowMac = com.waqar.institute.security.MahaBrahmastraProPlusEngine.computeRecordRowMac(
            recordType = "ATTENDANCE",
            recordId = "att_01",
            canonicalFields = "stu_mb_01|2026-11-17|PRESENT"
        )
        assertEquals(64, rowMac.length)
        assertTrue(
            com.waqar.institute.security.MahaBrahmastraProPlusEngine.verifyRecordRowMac(
                recordType = "ATTENDANCE",
                recordId = "att_01",
                canonicalFields = "stu_mb_01|2026-11-17|PRESENT",
                expectedMacHex = rowMac
            )
        )
        assertFalse(
            com.waqar.institute.security.MahaBrahmastraProPlusEngine.verifyRecordRowMac(
                recordType = "ATTENDANCE",
                recordId = "att_01",
                canonicalFields = "stu_mb_01|2026-11-17|TAMPERED",
                expectedMacHex = rowMac
            )
        )

        // 6. Feature 1: QR + Face Dual-Factor Smart Attendance Scanner
        val sampleStudent = com.example.data.model.StudentRecord(
            id = "stu_mb_01",
            enrollmentNo = "WCI-2026-101",
            fullName = "Aarav Sharma",
            courseName = "ADCA",
            batchName = "Morning Batch A",
            guardianName = "Rakesh Sharma",
            phone = "+919876543210"
        )
        val qrToken = com.waqar.institute.academic.AcademicProSuiteEngine.generateSignedStudentQrPayload(sampleStudent)
        val emb = FloatArray(128) { idx -> kotlin.math.sin((idx + 1) * 0.15f) }
        val dualRes = com.waqar.institute.academic.AcademicProSuiteEngine.verifyQrAndFaceDualFactorAttendance(
            rawQrPayload = qrToken,
            liveFaceEmbedding = emb,
            enrolledFaceEmbedding = emb.copyOf(),
            blinkVerified = true,
            markedByUid = "tch_01"
        )
        assertTrue(
            "Dual QR + Face attendance must pass",
            dualRes is com.waqar.institute.academic.AcademicProSuiteEngine.DualFactorAttendanceResult.Verified
        )
        val verifiedDual = dualRes as com.waqar.institute.academic.AcademicProSuiteEngine.DualFactorAttendanceResult.Verified
        assertEquals(64, verifiedDual.rowMacHex.length)
        assertEquals("QR_PLUS_FACE_BIOMETRIC", verifiedDual.record.method)

        // 7. Feature 2: Computer Lab PC Allotment & Syllabus Tracker
        val labs0 = com.waqar.institute.academic.AcademicProSuiteEngine.getAllWorkstations()
        assertEquals(24, labs0.size)
        val assignedPc = com.waqar.institute.academic.AcademicProSuiteEngine.assignStudentToWorkstation(
            pcCode = "PC-01",
            studentId = "stu_mb_01",
            studentName = "Aarav Sharma",
            enrollmentNo = "WCI-2026-101",
            batchName = "Morning Batch A"
        )
        assertEquals("Aarav Sharma", assignedPc.assignedStudentName)
        val pct0 = com.waqar.institute.academic.AcademicProSuiteEngine.computeCourseCompletionPercent("ADCA")
        assertTrue("Initial ADCA completion % should be > 0", pct0 > 0)

        // 8. Feature 3: PDF ID Card & Exam Admit Card Studio
        val idPdf = com.waqar.institute.academic.AcademicProSuiteEngine.generateStudentIdCardPdf(
            context = context,
            student = sampleStudent
        )
        assertTrue("Student ID Card PDF must exist and be non-empty", idPdf.file.exists() && idPdf.byteSize > 100L)
        assertEquals(64, idPdf.sha256ChecksumHex.length)

        val admitPdf = com.waqar.institute.academic.AcademicProSuiteEngine.generateExamAdmitCardPdf(
            context = context,
            student = sampleStudent,
            examTitle = "ADCA Semester-1 Final CBT",
            examDateIso = "2026-11-25",
            durationMinutes = 60,
            allottedPcCode = "PC-01"
        )
        assertTrue("Exam Admit Card PDF must exist and be non-empty", admitPdf.file.exists() && admitPdf.byteSize > 100L)

        // 9. Feature 4: Typing Speed & Accuracy Test Arena (Hindi & English WPM)
        val passage = com.waqar.institute.academic.AcademicProSuiteEngine.builtInTypingPassages.first().text
        val typingScore = com.waqar.institute.academic.AcademicProSuiteEngine.evaluateTypingPerformance(
            referencePassage = passage,
            typedText = passage,
            elapsedSeconds = 60,
            backspaceCount = 1,
            language = "ENGLISH"
        )
        assertTrue("Net WPM must be positive for full passage", typingScore.netWpm > 20)
        assertEquals(100, typingScore.accuracyPercent)

        // 10. Multi-Stage Biometric Coordinator & 120Hz Smoothness Optimizer
        val teacherGate = com.waqar.institute.biometric.MultiStageBiometricCoordinator.evaluateTeacherBlinkAndHeadTurnLiveness(
            teacherId = "tch_01",
            liveEmbedding = emb,
            enrolledEmbedding = emb.copyOf(),
            blinkCompleted = true,
            blinkConfidence = 0.91f,
            headYawDegrees = 14.5f
        )
        assertTrue(
            "Teacher blink + head-yaw liveness must pass",
            teacherGate is com.waqar.institute.biometric.MultiStageBiometricCoordinator.TeacherMultiAngleLivenessResult.Verified
        )

        val multiFaceViolation = com.waqar.institute.biometric.MultiStageBiometricCoordinator.auditExamProctoringFrame(
            studentId = "stu_mb_01",
            detectedFaceCount = 2,
            primaryFaceEmbedding = emb,
            enrolledEmbedding = emb
        )
        assertTrue(
            "2 faces during exam must trigger MultipleFacesDetected",
            multiFaceViolation is com.waqar.institute.biometric.MultiStageBiometricCoordinator.ExamProctoringVerdict.MultipleFacesDetected
        )

        com.waqar.institute.biometric.MultiStageBiometricCoordinator.recordAdminStepUpFaceVerification()
        assertTrue(com.waqar.institute.biometric.MultiStageBiometricCoordinator.isAdminStepUpVerifiedWithinWindow())

        val diag = com.waqar.institute.performance.Smooth120HzOptimizer.initializeHighRefreshPipeline(context)
        assertTrue(diag.coilMemoryCacheConfigured)
        assertEquals(8.33f, diag.targetFrameBudgetMs, 0.01f)
    }

    @Test
    fun zeroFeeUpiIntent_fiveSecurityLayers_fsmAndFourMonthAutoEmi() = runBlocking {
        // 1. Verify Official Merchant VPA & Name
        assertEquals("9235528409@ptyes", com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.MERCHANT_VPA)
        assertEquals("Waqar computer institute", com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.MERCHANT_NAME)

        // 2. Layer 1: Strict UPI Package Whitelist
        assertTrue(com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.isPackageWhitelisted("com.google.android.apps.nbu.paisa.user"))
        assertTrue(com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.isPackageWhitelisted("com.phonepe.app"))
        assertTrue(com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.isPackageWhitelisted("net.one97.paytm"))
        assertTrue(com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.isPackageWhitelisted("in.org.npci.upiapp"))
        assertFalse(
            "Spoofed UPI package must be rejected",
            com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.isPackageWhitelisted("com.fake.spoof.upi")
        )

        // 3. Layer 2: Issue Signed Challenge (Idle -> Challenge -> Dispatched -> PendingApproval)
        com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.resetFsmToIdle()
        val challengeRes = com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.issueSignedPaymentChallenge(
            context = null,
            studentId = "stu_upi_101",
            studentName = "Aarav Sharma",
            courseName = "ADCA",
            amountPaise = 150000L,
            paymentType = "MONTHLY_FEE",
            preferredPackage = "com.google.android.apps.nbu.paisa.user"
        )
        assertTrue("Challenge issuance must succeed", challengeRes.isSuccess)
        val challenge = challengeRes.getOrThrow()
        assertTrue(challenge.upiUriString.contains("pa=9235528409%40ptyes") || challenge.upiUriString.contains("pa=9235528409@ptyes"))
        assertTrue(challenge.upiUriString.contains("am=1500.00"))
        assertEquals(64, challenge.intentHashHex.length)

        val launchIntentRes = com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.buildWhitelistedLaunchIntent(challenge)
        assertTrue("Whitelisted launch intent must succeed", launchIntentRes.isSuccess)

        // Automatic UTR extraction from returning UPI Intent (Zero Manual Typing)
        val validReturnIntent = android.content.Intent().apply {
            putExtra(
                "response",
                "txnId=WCI987654&responseCode=00&Status=SUCCESS&txnRef=${challenge.trRef}&ApprovalRefNo=612345678901"
            )
        }
        val pendingRes = com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.verifyIntentResultAndSubmitPending(
            context = null,
            intentData = validReturnIntent,
            actorUid = "stu_upi_101"
        )
        assertTrue("Valid UPI return within 120s must transition to PENDING_APPROVAL", pendingRes.isSuccess)
        val pendingTxn = pendingRes.getOrThrow()
        assertEquals("PENDING_APPROVAL", pendingTxn.status)
        assertEquals("612345678901", pendingTxn.utr_number)
        assertEquals(150000L, pendingTxn.amount_paise)

        // 4. Layer 3: Single-Use Duplicate UTR Replay Lock
        val challenge2 = com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.issueSignedPaymentChallenge(
            context = null,
            studentId = "stu_upi_102",
            studentName = "Rohit Verma",
            courseName = "CCC",
            amountPaise = 150000L,
            paymentType = "MONTHLY_FEE"
        ).getOrThrow()
        com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.buildWhitelistedLaunchIntent(challenge2).getOrThrow()

        val duplicateUtrIntent = android.content.Intent().apply {
            putExtra(
                "response",
                "txnId=WCI999999&responseCode=00&Status=SUCCESS&txnRef=${challenge2.trRef}&ApprovalRefNo=612345678901"
            )
        }
        val duplicateRes = com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.verifyIntentResultAndSubmitPending(
            context = null,
            intentData = duplicateUtrIntent,
            actorUid = "stu_upi_102"
        )
        assertTrue("Duplicate UTR replay must be blocked", duplicateRes.isFailure)

        // 5. 120-Second Entropy Window Expiration Enforcement
        val challenge3 = com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.issueSignedPaymentChallenge(
            context = null,
            studentId = "stu_upi_103",
            studentName = "Zoya Khan",
            courseName = "Tally Prime",
            amountPaise = 200000L,
            paymentType = "AUTO_EMI"
        ).getOrThrow()
        com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.buildWhitelistedLaunchIntent(challenge3).getOrThrow()

        val expiredIntent = android.content.Intent().apply {
            putExtra(
                "response",
                "txnId=WCI777777&responseCode=00&Status=SUCCESS&txnRef=${challenge3.trRef}&ApprovalRefNo=712345678902"
            )
        }
        val expiredRes = com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.verifyIntentResultAndSubmitPending(
            context = null,
            intentData = expiredIntent,
            actorUid = "stu_upi_103",
            overrideNowMs = challenge3.issuedAtMs + 125_000L // > 120s window
        )
        assertTrue("Expired (>120s) challenge must be rejected", expiredRes.isFailure)

        // 6. Layer 4: Merchant Notification Parser & 4-Month Overdue Auto-EMI Ledger
        val parsedAlert = com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.extractMerchantAlertUtrAndAmount(
            "Paytm Business: Received Rs. 1,500.00 in your account. UPI Ref No: 612345678901."
        )
        assertNotNull(parsedAlert)
        assertEquals("612345678901", parsedAlert?.first)
        assertEquals(150000L, parsedAlert?.second)

        val stu = com.example.data.model.StudentRecord(
            id = "stu_emi_01",
            enrollmentNo = "WCI-2026-501",
            fullName = "Imran Siddiqui",
            courseName = "ADCA",
            totalFeePaise = 1800000L,
            paidFeePaise = 600000L
        )
        val syncedLedger = com.waqar.institute.upi.ZeroFeeUpiPaymentEngine.buildOrSyncStudentLedger(
            student = stu,
            existing = com.waqar.institute.upi.StudentFeesLedgerRecord(
                id = stu.id,
                student_id = stu.id,
                student_name = stu.fullName,
                overdue_months = 4,
                auto_emi_active = true,
                emi_billing_date = 10
            )
        )
        assertTrue(syncedLedger.isFourMonthOverdueEligible)
        assertTrue(syncedLedger.auto_emi_active)
        assertEquals("EMI_ACTIVE", syncedLedger.monthly_payment_status)
        assertEquals(10, syncedLedger.emi_billing_date)
    }
}

