package com.example

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.data.model.StudentRecord
import com.example.exam.ExamQuestionItem
import com.example.exam.PdfMcqBankParser
import com.example.ui.designsystem.WciColors
import com.waqar.institute.admin.AdminPdfQuestionBankService
import com.waqar.institute.ipc.ZeroCopyIpcRingManager
import com.waqar.institute.ledger.ImmutableEventSourcedLedger
import com.waqar.institute.sandbox.MicroVmExamSandboxManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinalAuditFixesVerificationTest {

    @Test
    fun adminPdfQuestionBankService_normalizesQuestionsWithinFirestoreRulesBounds() {
        val raw = listOf(
            ExamQuestionItem(
                id = "Q1",
                category = "ADCA",
                questionText = "Which key opens Tally Company Info?",
                options = listOf("Alt+F3", "Alt+F1", "Ctrl+A", "F12"),
                correctOptionIndex = 0
            ),
            ExamQuestionItem(
                id = "Q2",
                category = "ADCA",
                questionText = "HTML stands for?",
                options = listOf("HyperText Markup Language", "HighText Machine Language", "HyperLoop Text", "None"),
                correctOptionIndex = 0
            )
        )

        val schema = AdminPdfQuestionBankService.transformQuestionsToFirestoreSchema(
            rawQuestions = raw,
            examTitle = "ADCA Mid-Term",
            courseName = "ADCA Diploma",
            batchName = "Morning Batch",
            durationMinutes = 45,
            requestedPerStudentCount = 2,
            scheduledDateIso = "2026-11-16 14:00-15:00",
            ownerUid = "admin_root"
        )

        assertEquals(2, schema.totalPoolSize)
        assertEquals(2, schema.perStudentQuestionCount)
        assertTrue(schema.durationMinutes in 5..360)
        assertTrue(schema.totalMarks in 1..1000)
        assertTrue(schema.passingMarks in 0..schema.totalMarks)

        val roundTrip = PdfMcqBankParser.parseCustomExamBank(schema.questionsJson, "ADCA Diploma")
        assertEquals(2, roundTrip.questions.size)
        assertEquals(2, roundTrip.perStudentCount)
    }

    @Test
    fun immutableEventSourcedLedger_chainsAndDetectsTampering() {
        val e1 = ImmutableEventSourcedLedger.appendImmutableEvent(
            nodeDeviceId = "node_audit_test",
            eventType = "TEST_INIT",
            actorId = "admin_waqar",
            payloadSummary = "Initial immutable entry"
        )
        val e2 = ImmutableEventSourcedLedger.appendImmutableEvent(
            nodeDeviceId = "node_audit_test",
            eventType = "TEST_SECOND",
            actorId = "admin_waqar",
            payloadSummary = "Second chained entry"
        )

        assertTrue(ImmutableEventSourcedLedger.verifyMerkleChainIntegrity())
        assertEquals(16, ImmutableEventSourcedLedger.latestMerkleRootHex().length)
        assertEquals(e1.currentEventHash, e2.prevEventHash)

        val isolatedList = listOf(
            e1.copy(sequenceIndex = 1L, prevEventHash = "0000000000000000000000000000000000000000000000000000000000000000", currentEventHash = "TAMPERED_HASH")
        )
        assertFalse(ImmutableEventSourcedLedger.verifyExternalMerkleChain(isolatedList))
    }

    @Test
    fun qrPayloadSuffixMatchesStudentIdCorrectly() {
        val roster = listOf(
            StudentRecord(id = "stu_a101", enrollmentNo = "WCI-2026-001", fullName = "Aarav Sharma"),
            StudentRecord(id = "stu_b202", enrollmentNo = "WCI-2026-002", fullName = "Zoya Khan")
        )
        val qrScanned = "WCI-STUDENT:WCI-2026-002:stu_b202"
        val extractedQuery = qrScanned.substringAfterLast(":").trim()
        val matched = roster.firstOrNull {
            it.id.equals(extractedQuery, ignoreCase = true) ||
                it.enrollmentNo.equals(extractedQuery, ignoreCase = true) ||
                it.fullName.contains(extractedQuery, ignoreCase = true)
        }
        assertNotNull(matched)
        assertEquals("stu_b202", matched?.id)
        assertEquals("Zoya Khan", matched?.fullName)
    }

    @Test
    fun darkThemeTextTokensMaintainHighContrastOnSapphireSurface() {
        val darkGlass = Color(0xFF182254)
        val bgLum = darkGlass.luminance()

        fun contrastRatio(fg: Color): Float {
            val l1 = maxOf(fg.luminance(), bgLum)
            val l2 = minOf(fg.luminance(), bgLum)
            return (l1 + 0.05f) / (l2 + 0.05f)
        }

        assertTrue("TextPrimaryDark contrast >= 12:1", contrastRatio(WciColors.TextPrimaryDark) >= 12f)
        assertTrue("TextSecondaryDark contrast >= 10:1", contrastRatio(WciColors.TextSecondaryDark) >= 10f)
        assertTrue("TextMutedDark contrast >= 8:1", contrastRatio(WciColors.TextMutedDark) >= 8f)
        assertTrue("TextDisabledDark contrast >= 5:1", contrastRatio(WciColors.TextDisabledDark) >= 5f)
        assertTrue("BiometricSubtext contrast >= 10:1", contrastRatio(WciColors.BiometricSubtext) >= 10f)
    }

    @Test
    fun nativeSandboxAndIpcManagersDegradeSafelyWhenNativeSoAbsentInJvm() {
        val vmStarted = MicroVmExamSandboxManager.activateIsolatedExamMicroVm()
        val seq = ZeroCopyIpcRingManager.publishPortalEventZeroCopy("M10_CBT_EXAM", "JVM_TEST")
        assertFalse(vmStarted)
        assertEquals(-1L, seq)
    }
}
