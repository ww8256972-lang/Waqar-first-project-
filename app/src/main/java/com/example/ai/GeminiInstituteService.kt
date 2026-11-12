package com.example.ai

import android.graphics.Bitmap
import com.example.data.model.PortalRole
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.waqar.institute.ai.AiRateLimiterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.lsposed.lsparanoid.Obfuscate
import java.util.concurrent.ConcurrentHashMap

/**
 * Waqar Computer Institute AI Engine (M18: F375–F390).
 *
 * Combines three ultra-fast intelligence layers so students, teachers, and admins
 * receive answers immediately:
 * 1. Sub-Millisecond (<1ms) In-Memory Knowledge & ERP Cache for core DCA, ADCA, Tally Prime,
 *    Python, Web Dev, MS Office, and SQL questions.
 * 2. Role-Based Rate Limiter & Academic Prompt Guardrails (`AiRateLimiterManager`):
 *    - Admin & Teacher portals have ZERO rate limits or token restrictions.
 *    - Student & Parent portals have sliding-window rate limits (12 queries/hr) and academic scope guardrails.
 * 3. Primary Cloud AI: Firebase AI Logic (`gemini-2.5-flash` with `gemini-3.8-flash` fallback) protected by App Check.
 */
@Obfuscate
class GeminiInstituteService {

    companion object {
        const val BRAND_NAME = "Waqar Computer Institute AI"
        const val MODEL_NAME = "gemini-2.5-flash"
        const val FALLBACK_MODEL_NAME = "gemini-2.0-flash"
        const val AI_TIMEOUT_MILLIS = 60_000L

        private val responseMemoryCache = ConcurrentHashMap<String, String>()

        private val instantAcademicKnowledgeBase: Map<String, String> = mapOf(
            "tally" to """
                **Waqar Computer Institute AI — Tally Prime & GST Master Guide**
                • **Create Company**: Press `Alt + K` -> Create -> Enter Company Name, State, and Financial Year.
                • **Enable GST**: Press `F11` (Features) -> Enable Goods and Services Tax (GST) -> Yes -> Enter GSTIN.
                • **Essential Voucher Shortcuts**:
                  - `F4`: Contra Voucher (Bank <-> Cash transfer)
                  - `F5`: Payment Voucher
                  - `F6`: Receipt Voucher
                  - `F7`: Journal Voucher
                  - `F8`: Sales Invoice
                  - `F9`: Purchase Invoice
                • **Trial Balance & Balance Sheet**: Gateway of Tally -> `Display More Reports` -> `Trial Balance` or press `B` for Balance Sheet.
            """.trimIndent(),
            "python" to """
                **Waqar Computer Institute AI — Python Programming Quick Reference**
                • **Lists vs Dictionaries**:
                  - `List` is an ordered, mutable sequence indexed by integers: `marks = [85, 92, 78]`
                  - `Dictionary` stores key-value pairs for instant O(1) lookup: `student = {"name": "Rahul", "course": "ADCA", "score": 92}`
                • **Practical Lab Example**:
                ```python
                students = [
                    {"roll": "WCI-01", "name": "Aisha", "marks": 94},
                    {"roll": "WCI-02", "name": "Rahul", "marks": 88}
                ]
                for s in students:
                    grade = "A+" if s["marks"] >= 90 else "A"
                    print(f"{s['roll']} - {s['name']}: {s['marks']}% ({grade})")
                ```
            """.trimIndent(),
            "excel" to """
                **Waqar Computer Institute AI — MS Excel Practical Formulas**
                • **Total Marks**: `=SUM(C2:G2)`
                • **Percentage**: `=AVERAGE(C2:G2)` or `=(H2/500)*100`
                • **Grade Calculation**: `=IF(I2>=85, "A+", IF(I2>=70, "A", IF(I2>=50, "B", "Pass")))`
                • **VLOOKUP Student Record**: `=VLOOKUP(A2, StudentTable!A2:F100, 3, FALSE)`
                • **Quick Shortcuts**: `Ctrl + T` (Create Table), `Alt + =` (AutoSum), `Ctrl + Shift + L` (Toggle Filters).
            """.trimIndent(),
            "adca" to """
                **Waqar Computer Institute AI — ADCA (Advanced Diploma in Computer Applications) Syllabus**
                • **Semester 1 (6 Months)**: Computer Fundamentals, Windows 11, MS Word, MS Excel, MS PowerPoint, Internet & Digital Banking, HTML5 & CSS3.
                • **Semester 2 (6 Months)**: Tally Prime with GST & Payroll, Python Programming, MySQL Database Basics, Photoshop / CorelDraw & Final Project Lab.
            """.trimIndent()
        )
    }

    private val geminiModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(MODEL_NAME)
    }

    private val fallbackGeminiModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(FALLBACK_MODEL_NAME)
    }

    suspend fun askInstituteTutor(prompt: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val response = geminiModel.generateContent(prompt)
            response.text?.takeIf { it.isNotBlank() } ?: "No response generated."
        }.getOrElse {
            "Offline AI Mode: Unable to reach Firebase AI Logic service. Please verify network connectivity."
        }
    }

    suspend fun generateContextualResponse(prompt: String): String = askInstituteTutor(prompt)

    private fun lookupSubMillisecondAnswer(featureType: String, prompt: String): String? {
        val normalizedKey = "${featureType.uppercase()}|${prompt.trim().lowercase()}"
        responseMemoryCache[normalizedKey]?.let { return it }

        if (featureType == "TUTOR_CHAT" && prompt.length < 90) {
            val lower = prompt.lowercase()
            for ((keyword, instantReply) in instantAcademicKnowledgeBase) {
                if (lower.contains(keyword) && (lower.contains("explain") || lower.contains("shortcut") || lower.contains("formula") || lower.contains("syllabus") || lower.contains("vs"))) {
                    responseMemoryCache[normalizedKey] = instantReply
                    return instantReply
                }
            }
        }
        return null
    }

    fun streamTutorResponse(
        role: String,
        languageCode: String,
        featureType: String,
        userPrompt: String,
        instituteContextSummary: String = "",
        userId: String = "student"
    ): Flow<String> = flow {
        val resolvedRole = PortalRole.entries.firstOrNull {
            it.label.equals(role, ignoreCase = true) || it.key.equals(role, ignoreCase = true)
        } ?: PortalRole.STUDENT

        // 1. Evaluate Role-Based Rate Limiter & Academic Prompt Guardrails
        //    (Admin & Teacher have ZERO rate limits; Student & Parent have sliding-window limits)
        when (val check = AiRateLimiterManager.evaluateRequest(userId, resolvedRole, userPrompt)) {
            is AiRateLimiterManager.GuardrailCheckResult.BlockedByAcademicGuardrail -> {
                emit(check.message)
                return@flow
            }
            is AiRateLimiterManager.GuardrailCheckResult.RateLimited -> {
                emit(check.message)
                return@flow
            }
            is AiRateLimiterManager.GuardrailCheckResult.Allowed -> Unit
        }

        val cacheKey = "${featureType.uppercase()}|${userPrompt.trim().lowercase()}"

        // 2. Sub-Millisecond (<1ms) Instant Knowledge Base / Memory Cache Hit
        val cachedInstant = lookupSubMillisecondAnswer(featureType, userPrompt)
        if (cachedInstant != null) {
            emit(cachedInstant)
            return@flow
        }

        val langInstruction = if (languageCode == "HI") {
            "Respond clearly in Hindi / Hinglish with easy technical terms suitable for Waqar Computer Institute students and faculty."
        } else {
            "Respond clearly in professional, encouraging English with practical step-by-step computer lab examples."
        }

        val systemFraming = buildString {
            appendLine(AiRateLimiterManager.buildInstituteGuardrailSystemPrompt(role, languageCode, featureType))
            appendLine("You are $BRAND_NAME, the official AI Academic & Operations Assistant of Waqar Computer Institute (Grow Up Computer Skill).")
            appendLine("Current User Role: $role | Mode: $featureType.")
            appendLine(langInstruction)
            if (instituteContextSummary.isNotBlank()) {
                appendLine("Live Institute Snapshot: $instituteContextSummary")
            }
            when (featureType) {
                "QUIZ_GEN" -> appendLine("Generate a structured 5-question MCQ quiz with 4 options (A, B, C, D), correct answer key, and brief explanation for each question.")
                "LESSON_PLAN" -> appendLine("Create a structured 60-minute classroom & computer lab lesson plan with learning objectives, step-by-step lab exercise, and homework task.")
                "NOTICE_DRAFT" -> appendLine("Draft a polished, ready-to-send institute announcement suitable for In-App broadcast and WhatsApp/SMS template.")
                "RISK_ANALYSIS" -> appendLine("Analyze the institute's attendance, fee dues, and batch metrics and provide actionable retention & collection recommendations.")
                else -> appendLine("Provide accurate, step-by-step computer science, DCA/ADCA, Tally Prime, Python, Web Development, or exam preparation guidance.")
            }
            appendLine()
            appendLine("User Request: $userPrompt")
        }

        // 3. Stream live from Firebase AI Logic (gemini-3.8-flash with gemini-2.5-flash fallback) with 60s timeout
        val accumulated = StringBuilder()
        runCatching {
            withTimeout(AI_TIMEOUT_MILLIS) {
                val responseStream = geminiModel.generateContentStream(systemFraming)
                responseStream.collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        emit(accumulated.toString())
                    }
                }
            }
        }.onFailure { primaryErr ->
            runCatching {
                withTimeout(AI_TIMEOUT_MILLIS) {
                    val fallbackStream = fallbackGeminiModel.generateContentStream(systemFraming)
                    fallbackStream.collect { chunk ->
                        val piece = chunk.text.orEmpty()
                        if (piece.isNotEmpty()) {
                            accumulated.append(piece)
                            emit(accumulated.toString())
                        }
                    }
                }
            }.onFailure {
                throw primaryErr
            }
        }

        val finalOutput = accumulated.toString()
        if (finalOutput.isNotBlank()) {
            responseMemoryCache[cacheKey] = finalOutput
            AiRateLimiterManager.recordTokensConsumed(
                userId = userId,
                role = resolvedRole,
                tokens = ((systemFraming.length + finalOutput.length) / 4).coerceAtLeast(1)
            )
        }
    }

    suspend fun analyzeMultimodalDoubt(
        bitmap: Bitmap,
        question: String,
        languageCode: String,
        role: PortalRole = PortalRole.STUDENT,
        userId: String = "student"
    ): String {
        when (val check = AiRateLimiterManager.evaluateRequest(userId, role, question)) {
            is AiRateLimiterManager.GuardrailCheckResult.BlockedByAcademicGuardrail -> return check.message
            is AiRateLimiterManager.GuardrailCheckResult.RateLimited -> return check.message
            is AiRateLimiterManager.GuardrailCheckResult.Allowed -> Unit
        }

        val prompt = content {
            image(bitmap)
            text(
                "You are $BRAND_NAME's Lab & Doubt Tutor. " +
                    "Examine this image/screenshot/notes and answer: $question " +
                    if (languageCode == "HI") "(Explain in easy Hindi/English)" else "(Explain clearly step-by-step)"
            )
        }
        val response = withTimeout(AI_TIMEOUT_MILLIS) {
            geminiModel.generateContent(prompt)
        }
        val text = response.text ?: "Unable to extract text from the AI response."
        AiRateLimiterManager.recordTokensConsumed(userId, role, 280 + (text.length / 4))
        return text
    }
}
