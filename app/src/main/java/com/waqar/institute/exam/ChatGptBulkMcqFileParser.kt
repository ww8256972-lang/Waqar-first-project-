package com.waqar.institute.exam

import org.json.JSONArray
import java.util.UUID

data class MasterMcqQuestion(
    val masterQuestionId: String,
    val questionText: String,
    val options: List<String>, // Always 4 options [A, B, C, D]
    val correctOptionIndex: Int // 0=A, 1=B, 2=C, 3=D (NEVER sent to Student UI)
)

data class StudentSafeMcqQuestion(
    val masterQuestionId: String,
    val displayNumber: Int,
    val questionText: String,
    val shuffledOptions: List<ShuffledOptionItem>
)

data class ShuffledOptionItem(
    val displayLetter: String, // "A", "B", "C", "D" shown to student
    val originalOptionIndex: Int, // Canonical index 0..3 for 100% accurate grading
    val optionText: String
)

/**
 * [B2] ChatGPT Bulk MCQ + Answer Key File Upload Engine.
 *
 * Parses 150+ MCQs with answers from `.txt`, `.md`, `.csv`, `.json`, or extracted `.pdf` text.
 * Supports:
 * - JSON arrays (`[{"question":..., "options":[...], "answer":"A"}]`)
 * - CSV rows (`question,optA,optB,optC,optD,answer`)
 * - ChatGPT Markdown / plain text blocks (`1. What is CPU?` / `A) ...` / `Answer: A`)
 * Strips `correctOptionIndex` completely when producing `StudentSafeMcqQuestion` payloads, while
 * returning an isolated `Map<String, Int>` Master Answer Key map for `/examAnswerKeys/{examId}`.
 */
object ChatGptBulkMcqFileParser {

    fun parseBulkQuestionsAndAnswers(rawContent: String): List<MasterMcqQuestion> {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. JSON Array Support
        if (trimmed.startsWith("[")) {
            runCatching {
                val arr = JSONArray(trimmed)
                val out = mutableListOf<MasterMcqQuestion>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val qText = obj.optString("question", obj.optString("q", "")).trim()
                    val optsArr = obj.optJSONArray("options")
                    val opts = mutableListOf<String>()
                    if (optsArr != null) {
                        for (j in 0 until optsArr.length()) {
                            opts.add(optsArr.optString(j).trim())
                        }
                    } else {
                        listOf("optA", "optB", "optC", "optD").forEach { key ->
                            val v = obj.optString(key, "").trim()
                            if (v.isNotBlank()) opts.add(v)
                        }
                    }
                    val ansRaw = obj.optString(
                        "answer",
                        obj.optString("correctIndex", obj.optString("correctOptionIndex", "A"))
                    ).trim()
                    val correctIdx = parseAnswerLetterOrIndex(ansRaw, opts)
                    if (qText.isNotBlank() && opts.size >= 4 && correctIdx in 0..3) {
                        out.add(
                            MasterMcqQuestion(
                                masterQuestionId = "q_${i + 1}_${UUID.randomUUID().toString().take(6)}",
                                questionText = qText,
                                options = opts.take(4),
                                correctOptionIndex = correctIdx
                            )
                        )
                    }
                }
                if (out.isNotEmpty()) return out
            }
        }

        // 2. ChatGPT Text / Markdown / Extracted PDF Block Parser
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val results = mutableListOf<MasterMcqQuestion>()
        var currentQ: String? = null
        val currentOpts = mutableListOf<String>()
        var currentAns: Int? = null

        val qStartRegex = Regex(
            """^(?:\*\*)?(?:Q(?:uestion)?\s*)?(\d{1,4})[.)\-:]\s+(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val optRegex = Regex(
            """^(?:\(?([A-Da-d])\)?[.)\-:]|\*?\s*([A-Da-d])[.)])\s+(.+)$"""
        )
        val ansRegex = Regex(
            """^(?:\*\*)?(?:Ans(?:wer)?|Correct(?:\s+Answer|\s+Option)?)(?:\*\*)?\s*[:\-=]\s*(.+)$""",
            RegexOption.IGNORE_CASE
        )

        fun flushIfComplete() {
            val q = currentQ
            val ans = currentAns
            if (!q.isNullOrBlank() && currentOpts.size >= 4 && ans != null && ans in 0..3) {
                results.add(
                    MasterMcqQuestion(
                        masterQuestionId = "q_${results.size + 1}_${UUID.randomUUID().toString().take(6)}",
                        questionText = q.removeSuffix("**").trim(),
                        options = currentOpts.take(4).toList(),
                        correctOptionIndex = ans
                    )
                )
            }
            currentQ = null
            currentOpts.clear()
            currentAns = null
        }

        for (line in lines) {
            val ansMatch = ansRegex.matchEntire(line)
            if (ansMatch != null) {
                currentAns = parseAnswerLetterOrIndex(ansMatch.groupValues[1], currentOpts)
                flushIfComplete()
                continue
            }
            val optMatch = optRegex.matchEntire(line)
            if (optMatch != null && currentQ != null) {
                currentOpts.add(optMatch.groupValues[3].trim())
                continue
            }
            val qMatch = qStartRegex.matchEntire(line)
            if (qMatch != null) {
                flushIfComplete()
                currentQ = qMatch.groupValues[2].trim()
                continue
            }
        }
        flushIfComplete()
        if (results.isNotEmpty()) return results

        // 3. CSV Fallback (`question,optA,optB,optC,optD,answer`)
        val csvResults = mutableListOf<MasterMcqQuestion>()
        for ((idx, line) in lines.withIndex()) {
            val cols = parseCsvRow(line)
            if (cols.size >= 6) {
                val qText = cols[0].trim()
                if (idx == 0 && qText.equals("question", ignoreCase = true)) continue
                val opts = cols.subList(1, 5).map { it.trim() }
                val ansIdx = parseAnswerLetterOrIndex(cols[5].trim(), opts)
                if (qText.isNotBlank() && opts.all { it.isNotBlank() } && ansIdx in 0..3) {
                    csvResults.add(
                        MasterMcqQuestion(
                            masterQuestionId = "q_${csvResults.size + 1}_${UUID.randomUUID().toString().take(6)}",
                            questionText = qText,
                            options = opts,
                            correctOptionIndex = ansIdx
                        )
                    )
                }
            }
        }
        return csvResults
    }

    /**
     * Extracts an isolated canonical Answer Key map (`masterQuestionId -> correctOptionIndex`)
     * so answer keys are stored ONLY in Admin/Server vault (`/examAnswerKeys/{examId}`).
     */
    fun extractAnswerKeyMap(questions: List<MasterMcqQuestion>): Map<String, Int> =
        questions.associate { it.masterQuestionId to it.correctOptionIndex }

    /**
     * Produces stripped student-safe question views containing zero answer key indices.
     */
    fun stripAnswerKeysForStudent(questions: List<MasterMcqQuestion>): List<StudentSafeMcqQuestion> =
        questions.mapIndexed { idx, q ->
            StudentSafeMcqQuestion(
                masterQuestionId = q.masterQuestionId,
                displayNumber = idx + 1,
                questionText = q.questionText,
                shuffledOptions = q.options.take(4).mapIndexed { optIdx, text ->
                    val letter = when (optIdx) {
                        0 -> "A"
                        1 -> "B"
                        2 -> "C"
                        else -> "D"
                    }
                    ShuffledOptionItem(
                        displayLetter = letter,
                        originalOptionIndex = optIdx,
                        optionText = text
                    )
                }
            )
        }

    private fun parseCsvRow(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    out.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(ch)
            }
        }
        out.add(cur.toString())
        return out
    }

    private fun parseAnswerLetterOrIndex(raw: String, opts: List<String>): Int {
        val clean = raw.replace("*", "").trim()
        val firstLetter = Regex("""^\(?([A-Da-d])\b""").find(clean)?.groupValues?.get(1)?.uppercase()
        when (firstLetter) {
            "A" -> return 0
            "B" -> return 1
            "C" -> return 2
            "D" -> return 3
        }
        clean.toIntOrNull()?.let { num ->
            if (num in 0..3) return num
            if (num in 1..4) return num - 1
        }
        val textIdx = opts.indexOfFirst { it.equals(clean, ignoreCase = true) }
        return if (textIdx in 0..3) textIdx else 0
    }
}
