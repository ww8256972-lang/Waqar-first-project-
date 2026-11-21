package com.waqar.institute

import com.example.data.model.BatchRecord
import com.example.data.model.ExamRecord
import com.example.data.model.ExamResult
import com.example.data.model.StudentRecord
import com.example.exam.ExamRepository
import com.example.exam.TamperProofExamClock
import com.example.viewmodel.TeacherScopeMatcher
import com.waqar.institute.academic.AcademicProSuiteEngine
import com.waqar.institute.exam.ChatGptBulkMcqFileParser
import com.waqar.institute.exam.PerStudentExamShuffleEngine
import com.waqar.institute.security.Ed25519DocumentSigner
import com.waqar.institute.security.InstituteEncryptedBackupManager
import com.waqar.institute.upi.UpiTransactionRecord
import com.waqar.institute.upi.ZeroFeeUpiPaymentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive JVM Verification Suite for the Waqar Institute ERP Enterprise Upgrades:
 * - Mandatory UPI Production Cleanup (Zero fake UTR, blocked status when no NPCI app)
 * - Group A: A3 Ed25519 Signatures, A5 SHA-256 Fee Hash-Chain integrity & tamper detection
 * - Group B: B1 24h Smart Exam Engine + 30s duration, B2 ChatGPT 150-MCQ Bulk Parser,
 *            B3 40-Student Per-Student Shuffling + Exact Percentage Grading
 * - Group C: C3 Batch Ranker Leaderboard Top-3 & Top-5 Missed Questions,
 *            C4 Auto Lab PC Seat Allotment (PC-01..PC-30, Shift 1 & Shift 2),
 *            C5 AES-256-GCM .wci Encrypted Backup round-trip & wrong-passphrase rejection
 * - Group D: D1-D3 Batch-Admin Teacher strict isolation & live batch student count
 */
class EnterpriseUpgradeVerificationTest {

    @Test
    fun mandatoryUpiCleanup_and_A5_feeHashChainIntegrityAndTamperDetection() {
        ZeroFeeUpiPaymentEngine.markLaunchBlockedNoUpiApp("com.google.android.apps.nbu.paisa.user")
        val fsm = ZeroFeeUpiPaymentEngine.fsmState.value
        assertTrue(fsm is com.waqar.institute.upi.UpiPaymentFsmState.Failed)
        val failed = fsm as com.waqar.institute.upi.UpiPaymentFsmState.Failed
        assertEquals("NO_OFFICIAL_UPI_APP_INSTALLED", failed.errorCode)
        assertTrue(failed.reason.contains("Google Pay", ignoreCase = true))

        val genesis = "GENESIS_WCI_LEDGER"
        val h1 = ZeroFeeUpiPaymentEngine.computeChainedFeeRecordHash(
            prevHash = genesis,
            txnId = "txn_01",
            studentId = "stu_01",
            amountPaise = 150000L,
            utrNumber = "556677889901",
            timestampMs = 1731888000000L
        )
        val chainedR1 = UpiTransactionRecord(
            id = "txn_01",
            student_id = "stu_01",
            student_name = "Candidate One",
            amount = 1500.0,
            amount_paise = 150000L,
            tr_ref = "WCI_REF_001",
            utr_number = "556677889901",
            status = "VERIFIED_PAID",
            timestamp = 1731888000000L,
            chain_index = 0L,
            prev_record_hash = genesis,
            record_sha256_hash = h1
        )

        val h2 = ZeroFeeUpiPaymentEngine.computeChainedFeeRecordHash(
            prevHash = h1,
            txnId = "txn_02",
            studentId = "stu_02",
            amountPaise = 200000L,
            utrNumber = "556677889902",
            timestampMs = 1731888060000L
        )
        val chainedR2 = UpiTransactionRecord(
            id = "txn_02",
            student_id = "stu_02",
            student_name = "Candidate Two",
            amount = 2000.0,
            amount_paise = 200000L,
            tr_ref = "WCI_REF_002",
            utr_number = "556677889902",
            status = "VERIFIED_PAID",
            timestamp = 1731888060000L,
            chain_index = 1L,
            prev_record_hash = h1,
            record_sha256_hash = h2
        )

        assertTrue(ZeroFeeUpiPaymentEngine.verifyFeeLedgerHashChain(listOf(chainedR1, chainedR2)))

        // Tamper amount on r1 -> hash chain MUST fail verification
        val tamperedR1 = chainedR1.copy(amount_paise = 100L)
        assertFalse(ZeroFeeUpiPaymentEngine.verifyFeeLedgerHashChain(listOf(tamperedR1, chainedR2)))
    }

    @Test
    fun groupA3_ed25519DocumentSigningAndTamperRejection() {
        val seed = "WCI_UNIT_TEST_HARDWARE_SEED_2026".toByteArray(Charsets.UTF_8)
        val canonicalPayload = "WCI-CERT-2026-0099|EN-099|Candidate Alpha|ADCA|A+|92.5|2026-12-01"
        val sigHex = Ed25519DocumentSigner.signDocumentCanonicalPayload(canonicalPayload, seed)

        assertEquals(128, sigHex.length)
        assertTrue(Ed25519DocumentSigner.verifyDocumentSignature(canonicalPayload, sigHex, seed))
        assertFalse(
            Ed25519DocumentSigner.verifyDocumentSignature(
                canonicalPayload.replace("A+", "A"),
                sigHex,
                seed
            )
        )
    }

    @Test
    fun groupB1_smart24HourScheduleAndExact30SecondExamDuration() {
        val exam30s = ExamRecord(
            id = "exm_30s",
            title = "Rapid 30s Typing Check",
            batchId = "bat_1",
            batchName = "Morning Batch",
            startTime24h = "14:30",
            durationMinutes = 1,
            durationSeconds = 30
        )
        assertEquals(30, exam30s.effectiveDurationSeconds)

        val exam45m = ExamRecord(
            id = "exm_45m",
            title = "ADCA Final",
            batchId = "bat_1",
            batchName = "Morning Batch",
            startTime24h = "09:00",
            durationMinutes = 45,
            durationSeconds = 0
        )
        assertEquals(2700, exam45m.effectiveDurationSeconds)

        val epoch24h = TamperProofExamClock.parse24HourScheduleEpochMs("2026-12-01", "14:30")
        assertTrue(epoch24h > 0L)
    }

    @Test
    fun groupB2_and_B3_chatGpt150McqBulkParse_40StudentDistinctShuffle_and_ExactPercentageGrading() {
        val sb = StringBuilder()
        for (i in 1..150) {
            val ansLetter = listOf("A", "B", "C", "D")[i % 4]
            sb.appendLine("$i. Computer Architecture Question #$i?")
            sb.appendLine("A) Alpha Choice $i")
            sb.appendLine("B) Beta Choice $i")
            sb.appendLine("C) Gamma Choice $i")
            sb.appendLine("D) Delta Choice $i")
            sb.appendLine("Answer: $ansLetter")
            sb.appendLine()
        }

        val parsed = ChatGptBulkMcqFileParser.parseBulkQuestionsAndAnswers(sb.toString())
        assertEquals(150, parsed.size)

        // Generate 40 per-student papers and confirm distinct ordering across all 40 students
        val studentFingerprints = (1..40).map { sIdx ->
            val paper = PerStudentExamShuffleEngine.buildStudentShuffledPaper(
                examId = "EXM_LAB_2026",
                studentId = "STU_%03d".format(sIdx),
                masterQuestions = parsed
            )
            assertEquals(150, paper.size)
            paper.take(15).joinToString(",") { q ->
                "${q.masterQuestionId}:${q.shuffledOptions.joinToString("") { it.originalOptionIndex.toString() }}"
            }
        }.toSet()
        assertEquals(40, studentFingerprints.size)

        // Grade 128 correct out of 150 -> exact percentage = 85.33%
        val answersMap = parsed.mapIndexed { idx, q ->
            if (idx < 128) q.masterQuestionId to q.correctOptionIndex
            else q.masterQuestionId to ((q.correctOptionIndex + 1) % 4)
        }.toMap()

        val summary = PerStudentExamShuffleEngine.gradeSubmissionWithExactPercentage(
            masterQuestions = parsed,
            selectedOriginalOptionByMasterQId = answersMap
        )
        assertEquals(128, summary.correctCount)
        assertEquals(22, summary.wrongCount)
        assertEquals(0, summary.unansweredCount)
        assertEquals("85.33%", summary.formattedPercentage)
        assertEquals("A", summary.grade)
        assertEquals("PASSED", summary.status)
    }

    @Test
    fun groupC3_batchRankerLeaderboardAndTopMissedQuestions() {
        val results = listOf(
            ExamResult(id = "r1", examId = "ex1", studentId = "s1", studentName = "Student C", marksObtained = 75, totalMarks = 100, exactPercentage = 75.0, formattedPercentage = "75.0%", grade = "B+", status = "PASSED"),
            ExamResult(id = "r2", examId = "ex1", studentId = "s2", studentName = "Student A", marksObtained = 96, totalMarks = 100, exactPercentage = 96.0, formattedPercentage = "96.0%", grade = "A+", status = "PASSED"),
            ExamResult(id = "r3", examId = "ex1", studentId = "s3", studentName = "Student B", marksObtained = 88, totalMarks = 100, exactPercentage = 88.0, formattedPercentage = "88.0%", grade = "A", status = "PASSED"),
            ExamResult(id = "r4", examId = "ex1", studentId = "s4", studentName = "Student D", marksObtained = 32, totalMarks = 100, exactPercentage = 32.0, formattedPercentage = "32.0%", grade = "F", status = "FAILED")
        )
        val submissions = listOf(
            mapOf("Q001" to false, "Q002" to false, "Q003" to true),
            mapOf("Q001" to false, "Q002" to false, "Q003" to true),
            mapOf("Q001" to false, "Q002" to false, "Q003" to true),
            mapOf("Q001" to true, "Q002" to false, "Q003" to false)
        )
        val report = ExamRepository.computeBatchLeaderboardAndWrongQuestionStats(
            examId = "ex1",
            results = results,
            submissions = submissions
        )
        assertEquals(4, report.leaderboard.size)
        assertEquals("Student A", report.leaderboard[0].result.studentName)
        assertEquals(1, report.leaderboard[0].rank)
        assertEquals("GOLD #1", report.leaderboard[0].podiumBadge)
        assertEquals("Student B", report.leaderboard[1].result.studentName)
        assertEquals("Student C", report.leaderboard[2].result.studentName)
        assertEquals("Q002", report.topMissedQuestions.first().questionId)
        assertEquals(100, report.topMissedQuestions.first().missRatePercent)
    }

    @Test
    fun groupC4_autoAllotLabPcSeats_handles30PcsAndMultipleShifts() {
        val batchStudents = (1..45).map { i ->
            StudentRecord(
                id = "stu_%03d".format(i),
                enrollmentNo = "EN-%03d".format(i),
                fullName = "Student %03d".format(i),
                batchId = "bat_morning",
                batchName = "Morning Batch"
            )
        }
        val allocations = AcademicProSuiteEngine.autoAllotExamLabPcSeats(batchStudents, labCapacity = 30)
        assertEquals(45, allocations.size)
        assertEquals("PC-01", allocations["stu_001"]?.seatCode)
        assertEquals("Shift 1", allocations["stu_001"]?.shiftLabel)
        assertEquals("PC-30", allocations["stu_030"]?.seatCode)
        assertEquals("Shift 1", allocations["stu_030"]?.shiftLabel)
        assertEquals("PC-01", allocations["stu_031"]?.seatCode)
        assertEquals("Shift 2", allocations["stu_031"]?.shiftLabel)
        assertEquals("PC-15", allocations["stu_045"]?.seatCode)
        assertEquals("Shift 2", allocations["stu_045"]?.shiftLabel)
    }

    @Test
    fun groupC5_encryptedWciBackupRoundTripAndWrongPassphraseRejection() {
        val sampleJson = """{"institute":"Waqar Computer Institute","students":[{"id":"s1","name":"Candidate One"}],"batches":[{"id":"b1"}]}"""
        val passphrase = "WaqarVaultStrongPass!2026"
        val encrypted = InstituteEncryptedBackupManager.encryptFullBackupPayload(sampleJson, passphrase)

        assertTrue(encrypted.size > sampleJson.length)
        assertNotEquals(sampleJson, String(encrypted, Charsets.UTF_8))

        val decrypted = InstituteEncryptedBackupManager.decryptFullBackupPayload(encrypted, passphrase)
        assertEquals(sampleJson, decrypted)

        var wrongPassFailed = false
        try {
            InstituteEncryptedBackupManager.decryptFullBackupPayload(encrypted, "WrongPass123")
        } catch (_: Exception) {
            wrongPassFailed = true
        }
        assertTrue("Decryption with wrong passphrase MUST fail AES-GCM authentication", wrongPassFailed)
    }

    @Test
    fun groupD_batchAdminTeacherIsolation_onlyAssignedBatchesVisible() {
        val batches = listOf(
            BatchRecord(
                id = "bat_a",
                code = "B-A",
                name = "Morning 8AM",
                courseName = "ADCA",
                teacherId = "stf_t1",
                teacherName = "Teacher One",
                batchAdminTeacherId = "stf_t1",
                batchAdminTeacherName = "Teacher One"
            ),
            BatchRecord(
                id = "bat_b",
                code = "B-B",
                name = "Evening 5PM",
                courseName = "Tally",
                teacherId = "stf_t2",
                teacherName = "Teacher Two",
                batchAdminTeacherId = "stf_t2",
                batchAdminTeacherName = "Teacher Two"
            )
        )

        val scopedForT1 = TeacherScopeMatcher.filterBatchesForTeacher(
            batches = batches,
            teacherIdKeys = setOf("stf_t1"),
            teacherNameKeys = setOf("teacher one"),
            teacherNameSlugs = setOf("teacherone")
        )
        assertEquals(1, scopedForT1.size)
        assertEquals("bat_a", scopedForT1.first().id)

        val scopedForT2 = TeacherScopeMatcher.filterBatchesForTeacher(
            batches = batches,
            teacherIdKeys = setOf("stf_t2"),
            teacherNameKeys = setOf("teacher two"),
            teacherNameSlugs = setOf("teachertwo")
        )
        assertEquals(1, scopedForT2.size)
        assertEquals("bat_b", scopedForT2.first().id)
    }
}
