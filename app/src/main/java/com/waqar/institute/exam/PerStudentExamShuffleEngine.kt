package com.waqar.institute.exam

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt
import kotlin.random.Random

data class ExactExamGradingSummary(
    val totalQuestions: Int,
    val correctCount: Int,
    val wrongCount: Int,
    val unansweredCount: Int,
    val marksObtained: Int,
    val totalMarks: Int,
    val exactPercentage: Double,
    val formattedPercentage: String,
    val grade: String,
    val status: String // "PASSED" | "FAILED"
)

/**
 * [B3] Per-Student Cryptographic Question & Option Shuffling + Exact Percentage Grader.
 *
 * Uses `HMAC-SHA256(examId + "|" + studentId)` to deterministically Fisher-Yates shuffle BOTH
 * question order and option order per student (40+ students sitting side-by-side receive completely
 * distinct papers), while keeping canonical `masterQuestionId` + `originalOptionIndex` for
 * 100% zero-error grading and exact percentage calculation.
 */
object PerStudentExamShuffleEngine {
    private val LETTERS = listOf("A", "B", "C", "D")

    private fun deriveStudentExamSeed(examId: String, studentId: String): Long {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("WCI_EXAM_SHUFFLE_KEY_2026".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$examId|$studentId".toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(digest, 0, 8).long
    }

    /**
     * Generates a unique, deterministic shuffled paper for each student (e.g., 40 students get
     * 40 completely different question orders & option orders) with Answer Keys stripped out.
     */
    fun buildStudentShuffledPaper(
        examId: String,
        studentId: String,
        masterQuestions: List<MasterMcqQuestion>
    ): List<StudentSafeMcqQuestion> {
        val rng = Random(deriveStudentExamSeed(examId, studentId))
        val shuffledMaster = masterQuestions.shuffled(rng)

        return shuffledMaster.mapIndexed { idx, q ->
            val indexedOpts = q.options.mapIndexed { origIdx, text -> origIdx to text }.shuffled(rng)
            val displayOpts = indexedOpts.mapIndexed { dispIdx, (origIdx, text) ->
                ShuffledOptionItem(
                    displayLetter = LETTERS.getOrElse(dispIdx) { "A" },
                    originalOptionIndex = origIdx,
                    optionText = text
                )
            }
            StudentSafeMcqQuestion(
                masterQuestionId = q.masterQuestionId,
                displayNumber = idx + 1,
                questionText = q.questionText,
                shuffledOptions = displayOpts
            )
        }
    }

    /**
     * Matches student's selected canonical `originalOptionIndex` against `MasterMcqQuestion.correctOptionIndex`
     * with 100% accuracy regardless of how questions or options were shuffled, and computes exact percentage.
     */
    fun gradeSubmissionWithExactPercentage(
        masterQuestions: List<MasterMcqQuestion>,
        selectedOriginalOptionByMasterQId: Map<String, Int>,
        marksPerQuestion: Int = 1,
        passingPercentage: Double = 40.0
    ): ExactExamGradingSummary {
        var correct = 0
        var wrong = 0
        var unanswered = 0

        for (q in masterQuestions) {
            val selectedOrig = selectedOriginalOptionByMasterQId[q.masterQuestionId]
            when {
                selectedOrig == null -> unanswered++
                selectedOrig == q.correctOptionIndex -> correct++
                else -> wrong++
            }
        }

        val totalQ = masterQuestions.size.coerceAtLeast(1)
        val totalMarks = totalQ * marksPerQuestion.coerceAtLeast(1)
        val marksObtained = correct * marksPerQuestion.coerceAtLeast(1)
        val exactPct = (marksObtained.toDouble() * 100.0) / totalMarks.toDouble()
        val roundedPct = (exactPct * 100.0).roundToInt() / 100.0
        val grade = when {
            exactPct >= 90.0 -> "A+"
            exactPct >= 80.0 -> "A"
            exactPct >= 70.0 -> "B+"
            exactPct >= 60.0 -> "B"
            exactPct >= 50.0 -> "C"
            exactPct >= passingPercentage -> "D"
            else -> "F"
        }

        return ExactExamGradingSummary(
            totalQuestions = masterQuestions.size,
            correctCount = correct,
            wrongCount = wrong,
            unansweredCount = unanswered,
            marksObtained = marksObtained,
            totalMarks = totalMarks,
            exactPercentage = exactPct,
            formattedPercentage = "$roundedPct%",
            grade = grade,
            status = if (exactPct >= passingPercentage) "PASSED" else "FAILED"
        )
    }
}
