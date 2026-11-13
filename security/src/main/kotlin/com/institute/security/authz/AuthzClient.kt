package com.institute.security.authz

import com.institute.security.SecurityConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class AuthzClient(
    private val cfg: SecurityConfig,
    private val client: OkHttpClient,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(val allowed: Boolean, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * UI hint only. Real enforcement always lives on the gateway/OpenFGA server.
     * Results are cached for 60 seconds and fail-closed on errors.
     */
    suspend fun can(relation: String, obj: String): Boolean = withContext(Dispatchers.IO) {
        val key = "$relation|$obj"
        val now = clockMs()
        cache[key]?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return@withContext it.allowed }

        val json = JSONObject()
            .put("relation", relation)
            .put("object", obj)
            .toString()

        val req = Request.Builder()
            .url("${cfg.gatewayBaseUrl.trimEnd('/')}/v1/authz/check")
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val allowed = try {
            client.newCall(req).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                response.isSuccessful && raw.isNotBlank() && JSONObject(raw).optBoolean("allowed", false)
            }
        } catch (_: Exception) {
            false // Fail-closed for UI gate on network/gateway failure
        }

        cache[key] = Entry(allowed, clockMs())
        allowed
    }

    fun clear() = cache.clear()

    private companion object {
        const val CACHE_TTL_MS = 60_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
