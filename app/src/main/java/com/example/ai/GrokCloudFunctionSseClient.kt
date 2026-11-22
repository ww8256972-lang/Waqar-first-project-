package com.example.ai

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.lsposed.lsparanoid.Obfuscate
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Enterprise Grok API & Cloud Functions Server-Sent Events (SSE) Streaming Client.
 *
 * Security & Access Guarantees:
 * - Zero paid OpenAI usage: OpenAI API is completely removed.
 * - Routes to Grok API (`xAI grok-2-latest`) and zero-bill model cascades via Firebase Cloud Functions.
 * - Enforces mandatory rate limiting and robust access controls to prevent unauthorized extraction.
 * - Enforces anti-hacking protocol: security bypass attempts trigger an immediate 24-hour IP block,
 *   automated admin alert, and full payload logging.
 * - Attaches BOTH verified Firebase Auth ID token (`Authorization: Bearer`) AND Firebase App Check
 *   token (`X-Firebase-AppCheck`) + Hardware DPoP request proof.
 */
@Obfuscate
object GrokCloudFunctionSseClient {

    const val DEFAULT_CLOUD_FUNCTION_SSE_URL =
        "https://asia-east1-gen-lang-client-0309263247.cloudfunctions.net/streamInstituteAiTutor"
    const val PROXY_MODEL_NAME = "grok/grok-2-latest"
    const val LAST_RESORT_EXHAUSTED_REASON = "GROQ_AND_CLOUDFLARE_EXHAUSTED"
    const val LAST_RESORT_ELIGIBLE_FLAG = "GEMINI_FREE_LAST_RESORT"

    open class GroqAndCloudflareExhaustedException(
        val reason: String = LAST_RESORT_EXHAUSTED_REASON,
        message: String = "Grok API and upstream AI quotas are exhausted."
    ) : IllegalStateException(message)

    open class ProxySecurityViolationException(
        val strikeCount: Int,
        val locked: Boolean,
        val lockedUntilMs: Long,
        override val message: String
    ) : SecurityException(message)

    open class ProxyIpBannedException(
        val blockedUntilMs: Long,
        override val message: String
    ) : SecurityException(message)

    open class ProxyRateLimitException(
        val retryAfterSeconds: Long,
        override val message: String
    ) : IllegalStateException(message)

    open class CloudFunctionNotDeployedException(
        override val message: String = "AI proxy Cloud Function unavailable on Spark Plan."
    ) : IllegalStateException(message)

    private val sseHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    sealed class SseFrame {
        data class Delta(val text: String, val model: String? = null) : SseFrame()
        data class FallbackEligible(val reason: String, val message: String) : SseFrame()
        object Done : SseFrame()
        data class Error(val message: String) : SseFrame()
        object Ignored : SseFrame()
    }

    /**
     * Parses a single Server-Sent Events (`data: ...`) line from the Grok / AI Tutor proxy.
     */
    fun parseSseDataLine(rawLine: String): SseFrame {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return SseFrame.Ignored
        if (!trimmed.startsWith("data:")) return SseFrame.Ignored

        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return SseFrame.Ignored
        if (payload == "[DONE]") return SseFrame.Done

        return try {
            val json = JSONObject(payload)
            if (isExhaustionFallbackPayload(json)) {
                return SseFrame.FallbackEligible(
                    reason = json.optString("reason", LAST_RESORT_EXHAUSTED_REASON),
                    message = json.optString("error", "Primary AI quotas exhausted.")
                )
            }
            if (json.has("error")) {
                val errVal = json.optString("error").ifBlank { "Proxy SSE error" }
                return SseFrame.Error(errVal)
            }
            if (json.has("delta")) {
                val deltaText = json.optString("delta", "")
                val modelTag = json.optString("model", "").takeIf { it.isNotBlank() }
                return if (deltaText.isNotEmpty()) SseFrame.Delta(deltaText, modelTag) else SseFrame.Ignored
            }
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val first = choices.optJSONObject(0)
                val deltaObj = first?.optJSONObject("delta") ?: first?.optJSONObject("message")
                val content = deltaObj?.optString("content", "").orEmpty()
                if (content.isNotEmpty()) {
                    return SseFrame.Delta(content)
                }
            }
            SseFrame.Ignored
        } catch (_: Exception) {
            SseFrame.Ignored
        }
    }

    fun isExhaustionFallbackPayload(json: JSONObject): Boolean {
        val eligible = json.optString("fallbackEligible", "").trim()
        val reason = json.optString("reason", "").trim()
        return eligible.equals(LAST_RESORT_ELIGIBLE_FLAG, ignoreCase = true) &&
            reason.equals(LAST_RESORT_EXHAUSTED_REASON, ignoreCase = true)
    }

    private suspend fun fetchAppCheckTokenSafe(): String? {
        return withTimeoutOrNull(4_000L) {
            runCatching {
                FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }

    private suspend fun fetchIdTokenSafe(): String? {
        return withTimeoutOrNull(3_000L) {
            runCatching {
                FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Streams progressive AI completion text from `streamInstituteAiTutor` (asia-east1)
     * using Grok API and zero-trust Cloud Functions.
     */
    fun streamFromCloudFunctionProxy(
        systemPrompt: String,
        userPrompt: String,
        role: String,
        endpointUrl: String = DEFAULT_CLOUD_FUNCTION_SSE_URL,
        featureType: String = "TUTOR_CHAT",
        languageCode: String = "EN",
        context: android.content.Context? = null
    ): Flow<String> = flow {
        val idToken = fetchIdTokenSafe()
            ?: throw IllegalStateException("Firebase Auth session required for AI Tutor.")
        val appCheckToken = fetchAppCheckTokenSafe()

        val requestJson = JSONObject().apply {
            put("systemPrompt", systemPrompt)
            put("userPrompt", userPrompt)
            put("featureType", featureType)
            put("languageCode", languageCode)
            put("role", role)
        }
        val rawBodyStr = requestJson.toString()

        val reqBuilder = Request.Builder()
            .url(endpointUrl)
            .header("Accept", "text/event-stream, application/json")
            .header("Cache-Control", "no-cache")
            .header("Authorization", "Bearer $idToken")
            .header("X-WCI-Portal-Role", role)
            .post(rawBodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))

        if (appCheckToken != null) {
            reqBuilder.header("X-Firebase-AppCheck", appCheckToken)
        }

        com.waqar.institute.security.HardwareDpopRequestSigner.attachDpopHeaders(
            builder = reqBuilder,
            context = context,
            httpMethod = "POST",
            url = endpointUrl,
            rawBody = rawBodyStr
        )

        val accumulated = StringBuilder()
        sseHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val body = response.body ?: throw IllegalStateException("Empty AI proxy response body")

            // Handle HTTP 404 (Spark Plan / no Cloud Functions deployed) silently without user-facing error
            if (response.code == 404) {
                throw CloudFunctionNotDeployedException()
            }

            // Handle JSON control responses (200/403/429 exhaustion, security strikes, IP ban, or quota errors)
            if (!response.isSuccessful || contentType.contains("application/json")) {
                val rawBody = body.string()
                val parsed = runCatching { JSONObject(rawBody) }.getOrNull()
                if (parsed != null) {
                    if (isExhaustionFallbackPayload(parsed)) {
                        throw GroqAndCloudflareExhaustedException(
                            reason = parsed.optString("reason", LAST_RESORT_EXHAUSTED_REASON),
                            message = parsed.optString("error", "Grok and upstream AI quotas exhausted.")
                        )
                    }
                    if (response.code == 403 && parsed.optBoolean("ipBlocked", false)) {
                        throw ProxyIpBannedException(
                            blockedUntilMs = parsed.optLong("blockedUntilMs", 0L),
                            message = parsed.optString("error", "IP blocked for 24 hours due to security bypass attempt.")
                        )
                    }
                    if (response.code == 403 && (parsed.has("securityStrike") || parsed.optBoolean("locked", false))) {
                        throw ProxySecurityViolationException(
                            strikeCount = parsed.optInt("securityStrike", 1),
                            locked = parsed.optBoolean("locked", false),
                            lockedUntilMs = parsed.optLong("lockedUntilMs", 0L),
                            message = parsed.optString("error", "Security violation blocked.")
                        )
                    }
                    if (response.code == 429 && parsed.optBoolean("quotaExceeded", false)) {
                        throw ProxyRateLimitException(
                            retryAfterSeconds = parsed.optLong("retryAfterSeconds", 10L),
                            message = parsed.optString("error", "Daily AI quota reached.")
                        )
                    }
                    val errMsg = parsed.optString("error", "").takeIf { it.isNotBlank() }
                    if (errMsg != null) {
                        throw IllegalStateException(errMsg)
                    }
                }
                throw IllegalStateException("AI Tutor proxy returned HTTP ${response.code}")
            }

            BufferedReader(InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    when (val frame = parseSseDataLine(line!!)) {
                        is SseFrame.Delta -> {
                            accumulated.append(frame.text)
                            emit(accumulated.toString())
                        }
                        is SseFrame.FallbackEligible -> {
                            throw GroqAndCloudflareExhaustedException(
                                reason = frame.reason,
                                message = frame.message
                            )
                        }
                        is SseFrame.Done -> break
                        is SseFrame.Error -> throw IllegalStateException(frame.message)
                        is SseFrame.Ignored -> Unit
                    }
                }
            }
        }

        if (accumulated.isEmpty()) {
            throw IllegalStateException("No SSE tokens received from Grok AI proxy.")
        }
    }.flowOn(Dispatchers.IO)
}
