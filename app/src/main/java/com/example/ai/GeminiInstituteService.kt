package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import com.example.data.model.PortalRole
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.waqar.institute.ai.AiRateLimiterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.lsposed.lsparanoid.Obfuscate
import java.util.concurrent.ConcurrentHashMap

/**
 * Waqar Computer Institute Zero-Bill AI Tutor Engine (`GeminiInstituteService.kt`).
 *
 * Strict 6-Stage Zero-Bill Waterfall:
 * - Stage 0 (Pre-Cache Guard, ₹0): 24-hour anti-hacking cage, Strike 1/2 security filter,
 *   active-exam anti-cheat block, strict Computer-Studies allowlist & anti-chit-chat filter.
 * - Stage 1 (L1, <1ms, ₹0): In-memory `ConcurrentHashMap` + token similarity cache (unlimited hits).
 * - Stage 2 (L2, <1ms, ₹0): Encrypted local Room + SQLCipher `AiResponseCacheDatabase` (unlimited hits).
 * - Stage 3 (L3, ₹0): Global Shared Firestore Cache (`ai_shared_cache/{canonicalSha256}`) with
 *   mathematical prompt canonicalization + token-set Jaccard/Cosine similarity (`>= 0.78`).
 * - Stage 4 (L4 — Primary Live AI, ₹0): Authenticated `streamInstituteAiTutor` proxy (Groq Free Plan
 *   3,000 RPD via Cloudflare AI Gateway -> Cloudflare Workers AI `@cf/google/gemma-4-26b-a4b-it`).
 * - Stage 5 (L5 — STRICT LAST-RESORT GEMINI, ₹0): Firebase AI Logic (`gemini-3.8-flash` ->
 *   `gemini-3-flash`, paced strictly under `4 RPM / 18 RPD`, `maxOutputTokens = 350`, `temperature = 0.3f`)
 *   invoked **ONLY IF** Stage 4 explicitly returns `GROQ_AND_CLOUDFLARE_EXHAUSTED`.
 *   **NEVER called while Groq or Cloudflare has remaining quota.**
 * - Stage 6 (L6 — Offline Zero-Crash Fallback, ₹0): Graceful offline computer syllabus study guidance
 *   with daily-reset notice when even last-resort free quotas are exhausted or network is offline.
 */
@Obfuscate
class GeminiInstituteService(
    private val context: Context? = null,
    private val proxyStreamProvider: (
        systemPrompt: String,
        userPrompt: String,
        role: String,
        featureType: String,
        languageCode: String
    ) -> Flow<String> = { sys, usr, roleStr, feat, lang ->
        GrokCloudFunctionSseClient.streamFromCloudFunctionProxy(
            systemPrompt = sys,
            userPrompt = usr,
            role = roleStr,
            featureType = feat,
            languageCode = lang,
            context = context
        )
    },
    private val lastResortGeminiProvider: (suspend (prompt: String, onChunk: suspend (String) -> Unit) -> String)? = null
) {

    companion object {
        const val BRAND_NAME = "Waqar Computer Institute Study Tutor"
        const val MODEL_NAME = "gemini-3.8-flash"
        const val FALLBACK_MODEL_NAME = "gemini-3-flash"
        const val MAX_OUTPUT_TOKENS = 350
        const val AI_TIMEOUT_MILLIS = 60_000L

        private val responseMemoryCache = ConcurrentHashMap<String, String>()

        private val OFFLINE_SYLLABUS_NOTES: List<Pair<Regex, String>> = listOf(
            Regex("\\b(tally|gst|voucher|ledger|f8|f9)\\b", RegexOption.IGNORE_CASE) to
                "Offline Tally Prime Quick Note: Press F4 (Contra), F5 (Payment), F6 (Receipt), F7 (Journal), F8 (Sales Invoice), F9 (Purchase). Enable GST via F11 > Statutory & Taxation > Set/Alter GST Details.",
            Regex("\\b(python|list|tuple|dict|dictionary|loop|def)\\b", RegexOption.IGNORE_CASE) to
                "Offline Python Quick Note: Lists `[]` are mutable & ordered; Tuples `()` are immutable; Dictionaries `{k: v}` give O(1) key lookup. Define functions with `def name(args):` and iterate with `for item in iterable:`.",
            Regex("\\b(excel|vlookup|xlookup|pivot|formula|spreadsheet)\\b", RegexOption.IGNORE_CASE) to
                "Offline MS Excel Quick Note: `=VLOOKUP(lookup_value, table_array, col_index, FALSE)` searches vertically for exact matches. Use `=SUM(A1:A10)`, `=AVERAGE(...)`, and `=IF(cond, true_val, false_val)`.",
            Regex("\\b(sql|dbms|rdbms|normalization|primary\\s+key|join)\\b", RegexOption.IGNORE_CASE) to
                "Offline SQL/DBMS Quick Note: `SELECT col FROM table WHERE cond ORDER BY col;` retrieves rows. 1NF removes repeating groups, 2NF removes partial dependencies, 3NF removes transitive dependencies.",
            Regex("\\b(html|css|javascript|web)\\b", RegexOption.IGNORE_CASE) to
                "Offline Web Dev Quick Note: HTML5 structures content (`<header>`, `<main>`, `<section>`), CSS3 styles layout (Flexbox/Grid), and JavaScript adds interactivity (`document.querySelector`, `addEventListener`).",
            Regex("\\b(binary|hex|octal|logic\\s+gate|cpu|ram|rom|os|network|osi|tcp)\\b", RegexOption.IGNORE_CASE) to
                "Offline Computer Fundamentals Quick Note: CPU = ALU + CU + Registers. RAM is volatile primary memory; ROM is non-volatile. OSI has 7 layers: Physical, Data Link, Network, Transport, Session, Presentation, Application."
        )

        internal fun clearMemoryCacheForTests() {
            responseMemoryCache.clear()
        }
    }

    private val lastResortConfig by lazy {
        generationConfig {
            maxOutputTokens = MAX_OUTPUT_TOKENS
            temperature = 0.3f
        }
    }

    private val geminiModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL_NAME,
            generationConfig = lastResortConfig
        )
    }

    private val fallbackGeminiModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = FALLBACK_MODEL_NAME,
            generationConfig = lastResortConfig
        )
    }

    suspend fun askInstituteTutor(
        prompt: String,
        role: String = "Student",
        userId: String = "student"
    ): String = withContext(Dispatchers.IO) {
        var finalAnswer = ""
        streamTutorResponse(
            role = role,
            languageCode = "EN",
            featureType = "TUTOR_CHAT",
            userPrompt = prompt,
            userId = userId
        ).collect { partial ->
            finalAnswer = partial
        }
        finalAnswer.ifBlank { buildOfflineZeroCrashFallback(prompt) }
    }

    suspend fun generateContextualResponse(prompt: String): String = askInstituteTutor(prompt)

    private fun lookupSubMillisecondL1(featureType: String, languageCode: String, prompt: String): String? {
        val normalizedKey = "${featureType.uppercase()}|${languageCode.uppercase()}|${prompt.trim().lowercase()}"
        responseMemoryCache[normalizedKey]?.let { return it }
        AiLocalCacheRepository.getInstantL1CacheHit(featureType, languageCode, prompt)?.let { return it }
        return null
    }

    private fun buildOfflineZeroCrashFallback(userPrompt: String): String {
        val matchedNote = OFFLINE_SYLLABUS_NOTES.firstOrNull { (regex, _) ->
            regex.containsMatchIn(userPrompt)
        }?.second
        return buildString {
            if (matchedNote != null) {
                appendLine(matchedNote)
                appendLine()
            }
            append(
                "Offline Study Mode (₹0 Cost): Free live AI capacity has reached its daily reset limit or the network is unavailable. " +
                    "All previously cached answers remain available instantly, and live AI quotas reset automatically tomorrow."
            )
        }
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
        val normLang = languageCode.trim().uppercase().ifBlank { "EN" }

        // STAGE 0 — Pre-Cache Guardrails (0 API cost, 0 quota cost):
        // Checks 24-hour anti-hacking cage, Strike 1/2 security filter, active exam block,
        // strict Computer-Studies allowlist & Hinglish/English anti-chit-chat filter.
        when (val preCheck = AiRateLimiterManager.evaluatePreCacheGuardrails(userId, resolvedRole, userPrompt, context)) {
            is AiRateLimiterManager.GuardrailCheckResult.LockedOut24Hours -> {
                emit(preCheck.message)
                return@flow
            }
            is AiRateLimiterManager.GuardrailCheckResult.SecurityWarningStrikeOne -> {
                emit(preCheck.message)
                return@flow
            }
            is AiRateLimiterManager.GuardrailCheckResult.BlockedByAcademicGuardrail -> {
                emit(preCheck.message)
                return@flow
            }
            is AiRateLimiterManager.GuardrailCheckResult.RateLimited -> {
                emit(preCheck.message)
                return@flow
            }
            is AiRateLimiterManager.GuardrailCheckResult.Allowed -> Unit
        }

        val l1Key = "${featureType.uppercase()}|$normLang|${userPrompt.trim().lowercase()}"

        // STAGE 1 (L1): In-memory ConcurrentHashMap (< 1ms, unlimited cache hits)
        lookupSubMillisecondL1(featureType, normLang, userPrompt)?.let { l1Hit ->
            emit(l1Hit)
            return@flow
        }

        // STAGE 2 (L2): Local Room + SQLCipher AiResponseCacheDatabase (unlimited cache hits)
        val l2Hit = AiLocalCacheRepository.getCachedResponse(
            context = context,
            featureType = featureType,
            languageCode = normLang,
            prompt = userPrompt
        )
        if (!l2Hit.isNullOrBlank()) {
            responseMemoryCache[l1Key] = l2Hit
            emit(l2Hit)
            return@flow
        }

        // STAGE 3 (L3): Global Shared Firestore Cache (ai_shared_cache/{canonicalSha256} + Jaccard/Cosine >= 0.78)
        val l3Hit = AiLocalCacheRepository.getSharedFirestoreCacheHit(
            context = context,
            featureType = featureType,
            languageCode = normLang,
            prompt = userPrompt
        )
        if (!l3Hit.isNullOrBlank()) {
            responseMemoryCache[l1Key] = l3Hit
            emit(l3Hit)
            return@flow
        }

        // LIVE QUOTA GATE (Students/Parents: 5 live calls/day + 10s monotonic cooldown; Admin/Teacher exempt)
        when (val quotaCheck = AiRateLimiterManager.checkAndConsumeLiveQuota(userId, resolvedRole, context)) {
            is AiRateLimiterManager.GuardrailCheckResult.RateLimited -> {
                emit(quotaCheck.message)
                return@flow
            }
            else -> Unit
        }

        val systemFraming = buildString {
            appendLine(AiRateLimiterManager.buildInstituteGuardrailSystemPrompt(role, normLang, featureType))
            if (instituteContextSummary.isNotBlank()) {
                appendLine("Live Institute Snapshot: $instituteContextSummary")
            }
            when (featureType) {
                "QUIZ_GEN" -> appendLine("Generate a concise 5-question MCQ quiz with options (A, B, C, D) and answer key.")
                "LESSON_PLAN" -> appendLine("Create a concise 60-minute computer lab lesson plan with objectives and lab task.")
                "NOTICE_DRAFT" -> appendLine("Draft a polished institute announcement suitable for In-App broadcast.")
                "RISK_ANALYSIS" -> appendLine("Analyze attendance, fee dues, and batch metrics with concise recommendations.")
                else -> appendLine("Provide accurate, step-by-step computer science, DCA/ADCA, Tally Prime, Python, Web Dev, SQL, or exam preparation guidance.")
            }
        }

        // STAGE 4 (L4 — Primary Live AI): Authenticated streamInstituteAiTutor
        // (Groq 3,000 RPD via Cloudflare AI Gateway -> Cloudflare Workers AI 10K Neurons/day)
        val accumulated = StringBuilder()
        var modelUsed = GrokCloudFunctionSseClient.PROXY_MODEL_NAME
        var groqAndCloudflareExhausted = false
        var proxyHandledWithTerminalMessage = false

        try {
            withTimeout(AI_TIMEOUT_MILLIS) {
                proxyStreamProvider(
                    systemFraming,
                    userPrompt,
                    role,
                    featureType,
                    normLang
                ).collect { partialText ->
                    if (partialText.isNotEmpty()) {
                        accumulated.setLength(0)
                        accumulated.append(partialText)
                        emit(partialText)
                    }
                }
            }
        } catch (exhausted: GrokCloudFunctionSseClient.GroqAndCloudflareExhaustedException) {
            // ONLY this explicit exhaustion signal unlocks Stage 5 (Last-Resort Gemini)
            groqAndCloudflareExhausted = true
        } catch (_: GrokCloudFunctionSseClient.CloudFunctionNotDeployedException) {
            // Spark Plan (no Cloud Functions deployed): seamlessly fall back to Stage 5 Gemini Free Tier -> Stage 6 Offline Notes
            groqAndCloudflareExhausted = true
        } catch (banErr: GrokCloudFunctionSseClient.ProxyIpBannedException) {
            emit(banErr.message)
            proxyHandledWithTerminalMessage = true
        } catch (secErr: GrokCloudFunctionSseClient.ProxySecurityViolationException) {
            AiRateLimiterManager.mirrorServerSecurityLock(
                userId = userId,
                role = resolvedRole,
                strikeCount = secErr.strikeCount,
                locked = secErr.locked,
                lockedUntilEpochMs = secErr.lockedUntilMs,
                context = context
            )
            emit(secErr.message)
            proxyHandledWithTerminalMessage = true
        } catch (rateErr: GrokCloudFunctionSseClient.ProxyRateLimitException) {
            emit(rateErr.message)
            proxyHandledWithTerminalMessage = true
        } catch (_: Exception) {
            // Non-exhaustion failure (offline / transient error) MUST NOT silently call Gemini
            // while Groq/Cloudflare quota is not explicitly reported exhausted.
            groqAndCloudflareExhausted = false
        }

        if (proxyHandledWithTerminalMessage) {
            return@flow
        }

        // STAGE 5 (L5 — STRICT LAST-RESORT GEMINI):
        // Invoked ONLY IF Stage 4 explicitly returned GROQ_AND_CLOUDFLARE_EXHAUSTED
        // AND local monotonic pacer confirms < 4 RPM / 18 RPD.
        if (accumulated.isEmpty() && groqAndCloudflareExhausted) {
            if (AiRateLimiterManager.tryConsumeLastResortGeminiQuota()) {
                val combinedPrompt = "$systemFraming\n\nUser Request: $userPrompt"
                if (lastResortGeminiProvider != null) {
                    modelUsed = MODEL_NAME
                    runCatching {
                        val generated = lastResortGeminiProvider.invoke(combinedPrompt) { chunk ->
                            if (chunk.isNotEmpty()) {
                                accumulated.append(chunk)
                                emit(accumulated.toString())
                            }
                        }
                        if (accumulated.isEmpty() && generated.isNotBlank()) {
                            accumulated.append(generated)
                            emit(generated)
                        }
                    }
                } else {
                    modelUsed = MODEL_NAME
                    val primaryOk = runCatching {
                        withTimeout(AI_TIMEOUT_MILLIS) {
                            geminiModel.generateContentStream(combinedPrompt).collect { chunk ->
                                val piece = chunk.text.orEmpty()
                                if (piece.isNotEmpty()) {
                                    accumulated.append(piece)
                                    emit(accumulated.toString())
                                }
                            }
                        }
                        accumulated.isNotEmpty()
                    }.getOrDefault(false)

                    if (!primaryOk) {
                        accumulated.setLength(0)
                        modelUsed = FALLBACK_MODEL_NAME
                        runCatching {
                            withTimeout(AI_TIMEOUT_MILLIS) {
                                fallbackGeminiModel.generateContentStream(combinedPrompt).collect { chunk ->
                                    val piece = chunk.text.orEmpty()
                                    if (piece.isNotEmpty()) {
                                        accumulated.append(piece)
                                        emit(accumulated.toString())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // STAGE 6 (L6 — Offline Zero-Crash Fallback): Never crash and never incur ₹1 bill
        if (accumulated.isEmpty()) {
            emit(buildOfflineZeroCrashFallback(userPrompt))
            return@flow
        }

        // Save every verified live response from Stage 4 or Stage 5 back into L1, L2, and L3
        val finalOutput = accumulated.toString()
        responseMemoryCache[l1Key] = finalOutput
        AiLocalCacheRepository.putCachedResponse(
            context = context,
            featureType = featureType,
            languageCode = normLang,
            prompt = userPrompt,
            responseText = finalOutput,
            modelName = modelUsed,
            writeToSharedFirestore = true
        )
        AiRateLimiterManager.recordTokensConsumed(
            userId = userId,
            role = resolvedRole,
            tokens = ((systemFraming.length + finalOutput.length) / 4).coerceAtLeast(1)
        )
    }

    suspend fun analyzeMultimodalDoubt(
        bitmap: Bitmap,
        question: String,
        languageCode: String,
        role: PortalRole = PortalRole.STUDENT,
        userId: String = "student"
    ): String {
        when (val preCheck = AiRateLimiterManager.evaluatePreCacheGuardrails(userId, role, question, context)) {
            is AiRateLimiterManager.GuardrailCheckResult.LockedOut24Hours -> return preCheck.message
            is AiRateLimiterManager.GuardrailCheckResult.SecurityWarningStrikeOne -> return preCheck.message
            is AiRateLimiterManager.GuardrailCheckResult.BlockedByAcademicGuardrail -> return preCheck.message
            is AiRateLimiterManager.GuardrailCheckResult.RateLimited -> return preCheck.message
            is AiRateLimiterManager.GuardrailCheckResult.Allowed -> Unit
        }
        when (val quota = AiRateLimiterManager.checkAndConsumeLiveQuota(userId, role, context)) {
            is AiRateLimiterManager.GuardrailCheckResult.RateLimited -> return quota.message
            else -> Unit
        }
        if (!AiRateLimiterManager.tryConsumeLastResortGeminiQuota()) {
            return buildOfflineZeroCrashFallback(question)
        }

        val prompt = content {
            image(bitmap)
            text(
                "You are $BRAND_NAME. Examine this computer science/IT image/screenshot/notes and answer concisely (max 350 tokens): $question"
            )
        }
        val response = runCatching {
            withTimeout(AI_TIMEOUT_MILLIS) {
                geminiModel.generateContent(prompt)
            }
        }.getOrNull()
        val text = response?.text?.takeIf { it.isNotBlank() } ?: return buildOfflineZeroCrashFallback(question)
        AiRateLimiterManager.recordTokensConsumed(userId, role, 280 + (text.length / 4))
        return text
    }
}
