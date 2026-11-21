package com.example

import com.example.data.model.PortalRole
import com.example.exam.ActiveExamAntiCheatGuard
import com.example.exam.ExamQuestionItem
import com.example.exam.PdfMcqBankParser
import com.example.exam.QuestionBank
import com.waqar.institute.ai.AiRateLimiterManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfMcqBankParserAndAntiCheatTest {

    @org.junit.Before
    @After
    fun cleanupAntiCheatState() {
        ActiveExamAntiCheatGuard.clearActiveExamSession("stu_test_1")
        ActiveExamAntiCheatGuard.clearActiveExamSession("stu_test_2")
        ActiveExamAntiCheatGuard.clearActiveExamSession("stu_samaf")
        ActiveExamAntiCheatGuard.clearActiveExamSession("stu_mb_01")
        ActiveExamAntiCheatGuard.clearActiveExamSession("stu_sec_01")
    }

    @Test
    fun parseSerialMcqText_extractsSerialQuestionsOptionsAndRealAnswers() {
        val rawDocument = """
            Waqar Computer Institute Question Bank
            1. Which key creates a Payment Voucher in Tally Prime?
            A) F4
            B) F5
            C) F6
            D) F8
            Real Answer: B

            Q.2 What does CPU stand for?
            (A) Central Process Unit
            (B) Central Processing Unit
            (C) Computer Personal Unit
            (D) Central Program Unit
            Ans: B

            3) In Python, which collection is ordered and mutable?
            A. List
            B. Tuple
            C. Set
            D. Frozenset

            Answer Key:
            3-A
        """.trimIndent()

        val parsed = PdfMcqBankParser.parseSerialMcqText(rawDocument, "ADCA")
        assertEquals(3, parsed.size)

        assertEquals("PDF_Q001", parsed[0].id)
        assertTrue(parsed[0].questionText.contains("Payment Voucher"))
        assertEquals("F5", parsed[0].options[parsed[0].correctOptionIndex])
        assertEquals(1, parsed[0].correctOptionIndex)

        assertEquals("PDF_Q002", parsed[1].id)
        assertEquals("Central Processing Unit", parsed[1].options[parsed[1].correctOptionIndex])
        assertEquals(1, parsed[1].correctOptionIndex)

        assertEquals("PDF_Q003", parsed[2].id)
        assertEquals("List", parsed[2].options[parsed[2].correctOptionIndex])
        assertEquals(0, parsed[2].correctOptionIndex)
    }

    @Test
    fun extractTextFromPdfBytes_parsesEmbeddedPdfContentStream() {
        val pdfContent = """
            %PDF-1.4
            1 0 obj
            << /Length 210 >>
            stream
            BT
            /F1 12 Tf
            (1. Which Excel symbol starts every formula?) Tj
            (A\) = Equal) Tj
            (B\) # Hash) Tj
            (C\) @ At) Tj
            (D\) $ Dollar) Tj
            (Answer: A) Tj
            ET
            endstream
            endobj
            %%EOF
        """.trimIndent().toByteArray(StandardCharsets.ISO_8859_1)

        val extractedText = PdfMcqBankParser.extractTextFromPdfBytes(pdfContent)
        val parsed = PdfMcqBankParser.parseSerialMcqText(extractedText, "Excel")
        assertEquals(1, parsed.size)
        assertEquals("= Equal", parsed[0].options[parsed[0].correctOptionIndex])
    }

    @Test
    fun selectUniqueQuestionsForStudent_assignsDifferentPapersAndPreservesRealAnswer() {
        val pool = (1..40).map { i ->
            ExamQuestionItem(
                id = "PDF_Q%03d".format(i),
                category = "ADCA",
                questionText = "Question number $i about Tally and Python?",
                options = listOf("Correct-$i", "WrongB-$i", "WrongC-$i", "WrongD-$i"),
                correctOptionIndex = 0
            )
        }

        val paperStudentA = QuestionBank.selectUniqueQuestionsForStudent(pool, "stu_rahul_101", "exm_final", targetCount = 15)
        val paperStudentB = QuestionBank.selectUniqueQuestionsForStudent(pool, "stu_aisha_102", "exm_final", targetCount = 15)

        assertEquals(15, paperStudentA.size)
        assertEquals(15, paperStudentB.size)
        assertNotEquals(
            paperStudentA.map { it.questionText },
            paperStudentB.map { it.questionText }
        )

        // Verify real answer text stays 100% intact even after option shuffling
        paperStudentA.forEach { q ->
            val realAnswerText = q.options[q.correctOptionIndex]
            assertTrue("Expected shuffled real answer to start with Correct-, got $realAnswerText", realAnswerText.startsWith("Correct-"))
        }
        paperStudentB.forEach { q ->
            val realAnswerText = q.options[q.correctOptionIndex]
            assertTrue("Expected shuffled real answer to start with Correct-, got $realAnswerText", realAnswerText.startsWith("Correct-"))
        }
    }

    @Test
    fun activeExamAntiCheatGuard_blocksStudentAiQueriesDuringExamAndAllowsTeacherAdmin() {
        val sampleQuestion = ExamQuestionItem(
            id = "PDF_Q001",
            category = "Tally",
            questionText = "Which shortcut key is used in Tally Prime to post a Payment Voucher?",
            options = listOf("F4", "F5", "F6", "F8"),
            correctOptionIndex = 1
        )
        ActiveExamAntiCheatGuard.registerActiveExamSession("stu_test_1", listOf(sampleQuestion))

        val studentAttempt = AiRateLimiterManager.evaluateRequest(
            userId = "stu_test_1",
            role = PortalRole.STUDENT,
            promptText = "Which shortcut key is used in Tally Prime to post a Payment Voucher?"
        )
        assertTrue(studentAttempt is AiRateLimiterManager.GuardrailCheckResult.BlockedByAcademicGuardrail)

        val teacherQuery = AiRateLimiterManager.evaluateRequest(
            userId = "teacher_1",
            role = PortalRole.TEACHER,
            promptText = "Which shortcut key is used in Tally Prime to post a Payment Voucher?"
        )
        assertTrue(teacherQuery is AiRateLimiterManager.GuardrailCheckResult.Allowed)

        ActiveExamAntiCheatGuard.clearActiveExamSession("stu_test_1")
        val afterExamQuery = AiRateLimiterManager.evaluateRequest(
            userId = "stu_test_1",
            role = PortalRole.STUDENT,
            promptText = "Explain Tally Prime shortcuts for revision"
        )
        assertTrue(afterExamQuery is AiRateLimiterManager.GuardrailCheckResult.Allowed)
    }

    @Test
    fun serializeCustomExamBankJson_staysWithinFirestoreRulesLimit() {
        val hugePool = (1..150).map { i ->
            ExamQuestionItem(
                id = "PDF_Q%03d".format(i),
                category = "ADCA",
                questionText = "Long syllabus question number $i explaining computer networking OSI layers and TCP/IP routing protocol behavior?",
                options = listOf("Physical Layer $i", "Data Link Layer $i", "Network Layer $i", "Transport Layer $i"),
                correctOptionIndex = 2
            )
        }

        val serialized = PdfMcqBankParser.serializeCustomExamBankJson(hugePool, questionsPerStudent = 30)
        assertTrue("Serialized JSON length (${serialized.length}) must stay <= 20000 for firestore.rules", serialized.length <= 20000)

        val decoded = PdfMcqBankParser.parseCustomExamBank(serialized, "ADCA")
        assertTrue(decoded.questions.size >= 50)
        assertEquals(30, decoded.perStudentCount)
    }

    @Test
    fun adminPdfQuestionBankService_transformsPdfIntoNormalizedFirestoreSchema() {
        val rawPdfStream = """
            %PDF-1.4
            1 0 obj
            << /Length 260 >>
            stream
            BT
            /F1 12 Tf
            (1. Which protocol secures HTTPS web traffic?) Tj
            (A\) FTP) Tj
            (B\) TLS) Tj
            (C\) Telnet) Tj
            (D\) SMTP) Tj
            (Real Answer: B) Tj
            ET
            endstream
            endobj
            %%EOF
        """.trimIndent().toByteArray(StandardCharsets.ISO_8859_1)

        val result = com.waqar.institute.admin.AdminPdfQuestionBankService.parsePdfBytesToFirestoreSchema(
            pdfBytes = rawPdfStream,
            examTitle = "Cyber Security & Networking Quiz",
            courseId = "crs_adca",
            courseName = "ADCA Diploma",
            batchName = "Morning Batch A",
            durationMinutes = 45,
            marksPerQuestion = 2,
            passingPercentage = 50,
            ownerUid = "admin_root"
        )
        assertTrue(result.isSuccess)
        val schema = result.getOrThrow()
        assertEquals(1, schema.totalPoolSize)
        assertEquals("TLS", schema.normalizedQuestions[0].options[schema.normalizedQuestions[0].correctOptionIndex])

        val firestoreMap = schema.toFirestoreDocumentMap()
        val requiredKeys = setOf(
            "id", "title", "courseName", "batchName", "durationMinutes",
            "totalMarks", "passingMarks", "scheduledDateIso", "questionsJson", "createdAt"
        )
        assertTrue(firestoreMap.keys.containsAll(requiredKeys))
    }

    @Test
    fun teacherGpsGeofenceVerifier_enforces50mRadiusAndRejectsMockGps() {
        // 1. Inside 50m radius of 26.538162, 80.484187 (Unnao Institute)
        val insideLocation = android.location.Location("gps").apply {
            latitude = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.INSTITUTE_LATITUDE + 0.00010
            longitude = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.INSTITUTE_LONGITUDE + 0.00010
        }
        val insideEval = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.evaluateLocation(insideLocation)
        assertTrue("Expected InsidePremises for ~15m distance, got $insideEval",
            insideEval is com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.GeofenceVerificationResult.InsidePremises
        )

        // 2. Outside 50m radius (~350m away) -> must block and return exact alert message
        val outsideLocation = android.location.Location("gps").apply {
            latitude = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.INSTITUTE_LATITUDE + 0.00300
            longitude = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.INSTITUTE_LONGITUDE + 0.00300
        }
        val outsideEval = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.evaluateLocation(outsideLocation)
        assertTrue(
            outsideEval is com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.GeofenceVerificationResult.OutsidePremises
        )
        assertEquals(
            "Attendance Blocked: You must be inside the institute premises.",
            (outsideEval as com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.GeofenceVerificationResult.OutsidePremises).alertMessage
        )

        // 3. Anti-spoofing: Mock GPS location inside premises must be rejected
        val mockLocation = android.location.Location("gps").apply {
            latitude = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.INSTITUTE_LATITUDE
            longitude = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.INSTITUTE_LONGITUDE
            isMock = true
        }
        val mockEval = com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.evaluateLocation(mockLocation)
        assertTrue(
            "Mock GPS location must be rejected",
            mockEval is com.waqar.institute.biometric.TeacherGpsGeofenceVerifier.GeofenceVerificationResult.MockLocationRejected
        )
    }

    @Test
    fun codingSandboxBrahmastraSecurity_blocksExploitsAndExecutesSafeOfflineCode() {
        // 1. Verify AppDrawer exposes M25_CODING_SANDBOX across all 4 portals
        PortalRole.entries.forEach { role ->
            val allowed = com.example.ui.portal.allowedDrawerDestinationsForRole(role)
            assertTrue(
                "Expected M25_CODING_SANDBOX in drawer for $role",
                allowed.any {
                    it.routeId == com.waqar.institute.navigation.AppDrawer.ROUTE_CODING_SANDBOX &&
                        it.title == "Coding, Testing & Learning Lab"
                }
            )
        }

        // 2. Verify Pre-Execution AST & Regex Blacklist Guard blocks exploit strings
        val exploitSamples = listOf(
            "import os\nos.system('rm -rf /')",
            "import subprocess\nsubprocess.Popen(['sh'])",
            "import sys\nsys.exit(1)",
            "import shutil",
            "import ctypes",
            "import socket",
            "eval('2+2')",
            "exec('print(1)')",
            "fetch('https://evil.example.com')",
            "Runtime.getRuntime().exec('su')",
            "open('/data/data/com.aistudio.waqarinstitute.wciapp/files/secret')"
        )
        for (exploit in exploitSamples) {
            val scan = com.waqar.institute.terminal.CodingSandboxViewModel.inspectCodeForExploits(exploit)
            org.junit.Assert.assertFalse("Exploit sample must be blocked: $exploit", scan.isSafe)
        }

        // 3. Verify safe offline Python & JavaScript execution
        val pyOut = com.waqar.institute.terminal.CodingSandboxViewModel.evaluatePythonOfflineSandboxed(
            """
            name = "Waqar"
            total = 0
            for i in range(1, 5):
                total += i
            print(f"Institute: {name}, Sum: {total}")
            """.trimIndent()
        )
        assertEquals("Institute: Waqar, Sum: 10", pyOut)

        val jsOut = com.waqar.institute.terminal.CodingSandboxViewModel.evaluateJavaScriptOfflineSandboxed(
            """
            const scores = [10, 20, 30];
            let total = 0;
            for (let i = 0; i < scores.length; i++) {
                total += scores[i];
            }
            console.log("JS Total:", total);
            """.trimIndent()
        )
        assertEquals("JS Total: 60", jsOut)

        // 4. Verify Hard Watchdog / Loop Iteration Guard prevents infinite loop freezes
        var timedOut = false
        try {
            com.waqar.institute.terminal.CodingSandboxViewModel.executeWithHardWatchdog(300L) { stopSignal ->
                com.waqar.institute.terminal.CodingSandboxViewModel.evaluatePythonOfflineSandboxed(
                    "x = 1\nwhile True:\n    x = x + 1",
                    stopSignal = stopSignal
                )
            }
        } catch (e: Exception) {
            timedOut = true
        }
        assertTrue("Infinite loop must be terminated by watchdog/iteration guard", timedOut)

        // 5. Verify Volatile File System Jailing blocks path traversal and wipes sandbox dir
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val sandboxDir = java.io.File(context.cacheDir, "sandbox")
        com.waqar.institute.terminal.CodingSandboxViewModel.prepareJailedSandboxDirectory(sandboxDir)
        val safeFile = com.waqar.institute.terminal.CodingSandboxViewModel.resolveJailedFile(sandboxDir, "temp.txt")
        safeFile.writeText("offline_test")
        assertTrue(safeFile.exists())

        var traversalBlocked = false
        try {
            com.waqar.institute.terminal.CodingSandboxViewModel.resolveJailedFile(sandboxDir, "../../etc/passwd")
        } catch (e: SecurityException) {
            traversalBlocked = true
        }
        assertTrue("Path traversal outside cacheDir/sandbox/ must throw SecurityException", traversalBlocked)

        com.waqar.institute.terminal.CodingSandboxViewModel.wipeJailedSandboxDirectory(sandboxDir)
        org.junit.Assert.assertFalse("Volatile sandbox file must be wiped after execution", safeFile.exists())
    }

    @Test
    fun gitHubOtaUpdater_and_securityVault_verifyVersionsAndEncryptedStorage() {
        // 1. Semantic version comparison
        assertTrue(com.waqar.institute.updater.GitHubOtaUpdater.isNewerVersion("v1.0.1", "1.0"))
        assertTrue(com.waqar.institute.updater.GitHubOtaUpdater.isNewerVersion("v2.0", "1.9.9"))
        org.junit.Assert.assertFalse(com.waqar.institute.updater.GitHubOtaUpdater.isNewerVersion("v1.0.0", "1.0"))
        org.junit.Assert.assertFalse(com.waqar.institute.updater.GitHubOtaUpdater.isNewerVersion("v0.9.5", "1.0"))

        // 2. Parse GitHub release JSON payload
        val sampleReleaseJson = """
            {
              "tag_name": "v1.2.0",
              "name": "Waqar ERP v1.2.0 Enterprise Security Release",
              "body": "Added 50m GPS Geofence, Offline Coding Lab, and Kiosk Exam Lock.",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/waqar-institute/waqar-computer-institute-erp/releases/tag/v1.2.0",
              "assets": [
                {
                  "name": "WaqarInstitute-v1.2.0.apk",
                  "browser_download_url": "https://github.com/waqar-institute/waqar-computer-institute-erp/releases/download/v1.2.0/WaqarInstitute-v1.2.0.apk"
                }
              ]
            }
        """.trimIndent()

        val releaseInfo = com.waqar.institute.updater.GitHubOtaUpdater.parseReleaseJsonIfNewer(
            rawJson = sampleReleaseJson,
            currentVersionName = "1.0"
        )
        org.junit.Assert.assertNotNull(releaseInfo)
        assertEquals("v1.2.0", releaseInfo?.tagName)
        assertEquals("1.2.0", releaseInfo?.normalizedVersion)

        val sameVersionInfo = com.waqar.institute.updater.GitHubOtaUpdater.parseReleaseJsonIfNewer(
            rawJson = sampleReleaseJson,
            currentVersionName = "1.2.0"
        )
        org.junit.Assert.assertNull(sameVersionInfo)

        // 3. Hardware-backed SecurityVault encrypted string & 32-byte SQLCipher key
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.waqar.institute.security.SecurityVault.putSecureString(context, "test_api_token", "WCI_SECRET_2026")
        assertEquals("WCI_SECRET_2026", com.waqar.institute.security.SecurityVault.getSecureString(context, "test_api_token"))

        val sqlKey = com.waqar.institute.security.SecurityVault.getOrCreateSqlCipherPassphraseBytes(context)
        assertEquals(32, sqlKey.size)
    }

    @Test
    fun adminFaceVault_and_waqarPbkdf2Parity_verifyMasterPhotoAndConvertedAdmin() = kotlinx.coroutines.runBlocking {
        // 1. Verify PBKDF2-HMAC-SHA256 (12,000 iterations, 32-char hex CSPRNG salt) for `waqar`
        val waqarDigest = com.example.auth.Argon2CredentialHasher.hashPasswordPbkdf2WebParity("waqar".toCharArray())
        assertEquals(64, waqarDigest.hashHex.length)
        assertEquals(32, waqarDigest.saltHex.length)
        assertEquals(12_000, waqarDigest.iterations)
        assertTrue(
            com.example.auth.Argon2CredentialHasher.verifyPasswordConstantTime(
                passwordChars = "waqar".toCharArray(),
                storedEncodedHash = waqarDigest.hashHex,
                legacySaltHex = waqarDigest.saltHex
            )
        )
        org.junit.Assert.assertFalse(
            com.example.auth.Argon2CredentialHasher.verifyPasswordConstantTime(
                passwordChars = "wrong_password".toCharArray(),
                storedEncodedHash = waqarDigest.hashHex,
                legacySaltHex = waqarDigest.saltHex
            )
        )

        // 2. Verify bundled Master Admin photo asset is removed from APK and interactive enrollment into /adminFaces works
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        @Suppress("DEPRECATION")
        val bundledBitmap = com.waqar.institute.biometric.AdminFaceConversionAndGateManager.loadBundledMasterAdminBitmap(context)
        org.junit.Assert.assertNull("Bundled Master Admin photo asset must NOT exist in APK", bundledBitmap)

        // Create a realistic non-uniform portrait test bitmap (passes natural texture variance audit)
        val enrolledBitmap = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val r = ((x * 2 + (y % 17) * 5 + ((x xor y) and 0x1F)) % 220 + 25).coerceIn(0, 255)
                val g = ((y * 2 + (x % 13) * 6 + ((x * 3 + y) and 0x1F)) % 210 + 20).coerceIn(0, 255)
                val b = (((x + y) + ((x * y) % 31) * 4) % 200 + 20).coerceIn(0, 255)
                enrolledBitmap.setPixel(x, y, android.graphics.Color.rgb(r, g, b))
            }
        }

        val masterProfile = com.waqar.institute.biometric.AdminFaceConversionAndGateManager.enrollAdminFaceBitmapToVault(
            context = context,
            bitmap = enrolledBitmap
        )
        assertEquals(128, masterProfile.faceEmbedding.size)
        assertEquals("waqar", masterProfile.adminUsername)
        assertTrue(masterProfile.isPrimaryMaster)
        assertEquals("adminFaces", com.waqar.institute.biometric.AdminFaceConversionAndGateManager.ADMIN_FACES_COLLECTION)

        // Strong one-time Admin password policy verification
        val generatedOtp = com.waqar.institute.biometric.AdminFaceConversionAndGateManager.generateSecureOneTimeAdminPassword()
        assertTrue(
            "Generated OTP must satisfy strong 10+ char complexity policy",
            com.waqar.institute.biometric.AdminFaceConversionAndGateManager.isStrongAdminPassword(generatedOtp)
        )
        org.junit.Assert.assertFalse(
            "Weak slug123 passwords must be rejected",
            com.waqar.institute.biometric.AdminFaceConversionAndGateManager.isStrongAdminPassword("waqar123")
        )

        // Matching the enrolled Admin photo against the Admin Vault must succeed (>= 0.85 cosine similarity)
        val matchOutcome = com.waqar.institute.biometric.AdminFaceConversionAndGateManager.verifyLiveBitmapAgainstAdminVault(
            context = context,
            liveBitmap = enrolledBitmap
        )
        assertTrue(
            "Expected enrolled Admin photo to match Admin Face Vault, got $matchOutcome",
            matchOutcome is com.waqar.institute.biometric.AdminFaceConversionAndGateManager.AdminFaceMatchOutcome.Matched
        )

        // 3. Verify "Admin Conversion" route is in Admin Portal drawer only
        val adminRoutes = com.example.ui.portal.allowedDrawerDestinationsForRole(PortalRole.ADMIN).map { it.routeId }
        val studentRoutes = com.example.ui.portal.allowedDrawerDestinationsForRole(PortalRole.STUDENT).map { it.routeId }
        assertTrue(adminRoutes.contains(com.waqar.institute.biometric.AdminFaceConversionAndGateManager.ROUTE_ADMIN_CONVERSION))
        org.junit.Assert.assertFalse(studentRoutes.contains(com.waqar.institute.biometric.AdminFaceConversionAndGateManager.ROUTE_ADMIN_CONVERSION))
    }

    @Test
    fun verifyCertificateRollMapping_examCleanup_and_instantLocalCredentialLookup() {
        // 1. Certificate Data Mapping: Student Name -> studentName, Roll No -> certificateOrRollNo
        val student = com.example.data.model.StudentRecord(
            id = "stu_samaf",
            enrollmentNo = "WCI-2026-7048",
            fullName = "Samaf Khan",
            courseName = "ADCA (Advanced Diploma in Computer Applications)",
            status = "GRADUATED"
        )
        val certModel = com.waqar.institute.certificate.CertificateGeneratorViewModel.buildAutoFilledCertificate(
            student = student,
            issuedCert = null,
            courses = emptyList(),
            examResults = emptyList()
        )
        assertEquals("Samaf Khan", certModel.studentName)
        assertEquals("WCI-2026-7048", certModel.certificateOrRollNo)

        // 2. Exam Portal Cleanup: empty questionsJson returns emptyList() (no hardcoded dummy questions)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val examRepo = com.example.exam.ExamRepository(
            context = context,
            instituteRepository = com.example.data.firestore.InstituteRepository.create(context)
        )
        val emptyExam = com.example.data.model.ExamRecord(
            id = "exm_clean_1",
            title = "Empty Scheduled Exam",
            courseName = "ADCA",
            questionsJson = "[]"
        )
        val generatedPaper = examRepo.generateShuffledPaperForExam(
            studentId = "stu_samaf",
            exam = emptyExam
        )
        assertTrue("Expected empty question list when exam has no database questions", generatedPaper.isEmpty())

        // 3. Instant Local Credential Cache Lookup (<5ms) by Username and Enrollment No
        val cred = com.example.data.model.PortalCredential(
            username = "samaf_wci",
            passwordHash = "hash",
            role = "student",
            displayName = "Samaf Khan",
            linkedEntityId = "stu_samaf",
            active = true
        )
        com.example.data.firestore.InstituteRepository.localCredentialsCache["samaf_wci"] = cred
        com.example.data.firestore.InstituteRepository.localStudentsCache["stu_samaf"] = student

        val startNs = System.nanoTime()
        val byUsername = com.example.data.firestore.InstituteRepository.getCachedCredentialLocally("samaf_wci")
        val byRollNo = com.example.data.firestore.InstituteRepository.getCachedCredentialLocally("WCI-2026-7048")
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        org.junit.Assert.assertNotNull("Expected instant match by username", byUsername)
        org.junit.Assert.assertNotNull("Expected instant match by student roll number", byRollNo)
        assertEquals("samaf_wci", byRollNo?.username)
        assertTrue("Expected sub-15ms local credential resolution, took ${elapsedMs}ms", elapsedMs < 15L)
    }
}



