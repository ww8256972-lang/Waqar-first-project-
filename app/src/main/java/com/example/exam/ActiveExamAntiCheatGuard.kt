package com.example.exam

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-Time Anti-Cheating Guard shared between the Proctored Exam Engine (`ExamViewModel`)
 * and the In-App AI Tutor (`AiRateLimiterManager` / `GeminiInstituteService`).
 *
 * Guarantees:
 * 1. When a student enters an active CBT exam session, the AI Tutor refuses to solve
 *    questions matching any active exam bank question.
 * 2. Stores normalized question fingerprints of published exams in memory so live cheating
 *    queries pasted or typed into the chatbot during an exam are blocked automatically.
 * 3. Admin and Teacher portals bypass this block for paper creation & verification.
 */
object ActiveExamAntiCheatGuard {

    private val studentExamActive = AtomicBoolean(false)
    private val activeExamStudentId = ConcurrentHashMap.newKeySet<String>()
    private val activeQuestionTokens = ConcurrentHashMap<String, Set<String>>()

    fun registerActiveExamSession(studentId: String, questions: List<ExamQuestionItem>) {
        studentExamActive.set(true)
        if (studentId.isNotBlank()) {
            activeExamStudentId.add(studentId.trim().lowercase())
        }
        questions.forEach { q ->
            val tokens = tokenizeQuestion(q.questionText)
            if (tokens.size >= 3) {
                activeQuestionTokens[q.id] = tokens
            }
        }
    }

    fun clearActiveExamSession(studentId: String) {
        if (studentId.isNotBlank()) {
            activeExamStudentId.remove(studentId.trim().lowercase())
        }
        if (activeExamStudentId.isEmpty()) {
            studentExamActive.set(false)
            activeQuestionTokens.clear()
        }
    }

    fun isAnyStudentExamActive(): Boolean = studentExamActive.get()

    fun isStudentExamActive(userId: String): Boolean {
        if (!studentExamActive.get()) return false
        if (userId.isBlank()) return true
        return activeExamStudentId.contains(userId.trim().lowercase()) || activeExamStudentId.isNotEmpty()
    }

    /**
     * Checks whether a student prompt submitted to the AI Tutor matches an active exam question
     * or asks for live exam cheating assistance while an exam session is active.
     */
    fun shouldBlockAiCheatingQuery(userId: String, promptText: String): Boolean {
        if (!isStudentExamActive(userId)) return false
        val promptTokens = tokenizeQuestion(promptText)
        if (promptTokens.isEmpty()) return false

        // While a student is actively inside a proctored exam, block any academic solving query
        for ((_, qTokens) in activeQuestionTokens) {
            val overlap = promptTokens.intersect(qTokens).size
            val minSize = minOf(promptTokens.size, qTokens.size).coerceAtLeast(1)
            if (overlap >= 4 || (overlap.toDouble() / minSize.toDouble()) >= 0.55) {
                return true
            }
        }
        return true
    }

    private fun tokenizeQuestion(raw: String): Set<String> {
        val stopWords = setOf(
            "what", "which", "who", "whom", "whose", "where", "when", "why", "how",
            "the", "is", "are", "was", "were", "in", "on", "at", "of", "to", "for",
            "and", "or", "with", "from", "that", "this", "used", "into", "each", "answer"
        )
        return raw.lowercase()
            .replace(Regex("[^a-z0-9\\u0900-\\u097F\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in stopWords }
            .toSet()
    }
}
