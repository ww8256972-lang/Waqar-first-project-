package com.example.ai

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import org.lsposed.lsparanoid.Obfuscate

typealias SseFrame = GrokCloudFunctionSseClient.SseFrame

/**
 * Backward compatibility delegation to [GrokCloudFunctionSseClient].
 * OpenAI has been completely removed in favor of Grok API and zero-trust Cloud Functions.
 */
@Deprecated(
    message = "OpenAI API has been fully removed. Use GrokCloudFunctionSseClient.",
    replaceWith = ReplaceWith("GrokCloudFunctionSseClient")
)
@Obfuscate
object OpenAiCloudFunctionSseClient {

    const val DEFAULT_CLOUD_FUNCTION_SSE_URL = GrokCloudFunctionSseClient.DEFAULT_CLOUD_FUNCTION_SSE_URL
    const val PROXY_MODEL_NAME = GrokCloudFunctionSseClient.PROXY_MODEL_NAME
    const val LAST_RESORT_EXHAUSTED_REASON = GrokCloudFunctionSseClient.LAST_RESORT_EXHAUSTED_REASON
    const val LAST_RESORT_ELIGIBLE_FLAG = GrokCloudFunctionSseClient.LAST_RESORT_ELIGIBLE_FLAG

    class GroqAndCloudflareExhaustedException(
        reason: String = LAST_RESORT_EXHAUSTED_REASON,
        message: String = "Grok API and upstream AI quotas are exhausted."
    ) : GrokCloudFunctionSseClient.GroqAndCloudflareExhaustedException(reason, message)

    class ProxySecurityViolationException(
        strikeCount: Int = 1,
        locked: Boolean = false,
        lockedUntilMs: Long = 0L,
        message: String = "Security policy violation"
    ) : GrokCloudFunctionSseClient.ProxySecurityViolationException(strikeCount, locked, lockedUntilMs, message)

    class ProxyRateLimitException(
        retryAfterSeconds: Long = 60L,
        message: String = "AI proxy rate-limit exceeded."
    ) : GrokCloudFunctionSseClient.ProxyRateLimitException(retryAfterSeconds, message)

    class CloudFunctionNotDeployedException(
        message: String = "Firebase Cloud Function not deployed."
    ) : GrokCloudFunctionSseClient.CloudFunctionNotDeployedException(message)

    fun parseSseDataLine(rawLine: String): GrokCloudFunctionSseClient.SseFrame =
        GrokCloudFunctionSseClient.parseSseDataLine(rawLine)

    fun isExhaustionFallbackPayload(json: JSONObject): Boolean =
        GrokCloudFunctionSseClient.isExhaustionFallbackPayload(json)

    fun streamFromCloudFunctionProxy(
        systemPrompt: String,
        userPrompt: String,
        role: String,
        endpointUrl: String = DEFAULT_CLOUD_FUNCTION_SSE_URL,
        featureType: String = "TUTOR_CHAT",
        languageCode: String = "EN",
        context: android.content.Context? = null
    ): Flow<String> = GrokCloudFunctionSseClient.streamFromCloudFunctionProxy(
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        role = role,
        endpointUrl = endpointUrl,
        featureType = featureType,
        languageCode = languageCode,
        context = context
    )
}
