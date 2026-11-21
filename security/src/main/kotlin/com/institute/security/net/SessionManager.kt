package com.institute.security.net

import android.os.Build
import com.institute.security.SecurityConfig
import com.institute.security.SecurityKit
import com.institute.security.device.DeviceKeyManager
import com.institute.security.device.DpopSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object SessionManager {
    private const val REFRESH_MARGIN_MS = 30_000L
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val mutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedDeviceId: String? = null

    @Volatile
    private var expiresAtMs: Long = 0L

    @Volatile
    private var bootstrapClientOverride: OkHttpClient? = null

    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    private fun bootstrapClient(cfg: SecurityConfig): OkHttpClient =
        bootstrapClientOverride ?: SecureHttp.buildPinnedClient(cfg)

    /**
     * Returns a valid short-lived (10-min) DPoP-bound gateway JWT, enrolling the hardware device key
     * and exchanging the upstream identity token when missing or within 30s of expiry.
     */
    suspend fun accessToken(): String = mutex.withLock {
        val current = cachedToken
        if (!current.isNullOrBlank() && nowMs() < expiresAtMs - REFRESH_MARGIN_MS) {
            return@withLock current
        }

        val cfg = SecurityKit.config
        val idProvider = SecurityKit.identityProvider
            ?: throw IllegalStateException("SecurityKit.identityProvider is not configured")

        return@withLock withContext(Dispatchers.IO) {
            val client = bootstrapClient(cfg)
            val base = cfg.gatewayBaseUrl.trimEnd('/')
            val upstreamIdToken = idProvider()
            if (upstreamIdToken.isBlank()) {
                throw IllegalStateException("Upstream identity token is blank")
            }

            val deviceId = ensureEnrolled(client, base, upstreamIdToken)
            val exchangeResult = exchangeSession(client, base, upstreamIdToken, deviceId)
            cachedToken = exchangeResult.first
            expiresAtMs = exchangeResult.second
            exchangeResult.first
        }
    }

    private fun ensureEnrolled(client: OkHttpClient, base: String, upstreamIdToken: String): String {
        val existingDeviceId = cachedDeviceId
        if (!existingDeviceId.isNullOrBlank() && DeviceKeyManager.hasKey()) {
            return existingDeviceId
        }

        val challengeUrl = "$base/v1/device/challenge"
        val challengeReq = Request.Builder()
            .url(challengeUrl)
            .get()
            .build()

        val nonce = client.newCall(challengeReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Challenge failed: HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            json.optString("nonce").ifBlank { json.optString("challenge") }
        }
        if (nonce.isBlank()) throw IOException("Empty attestation nonce from gateway")

        check(DeviceKeyManager.ensureKey(nonce.toByteArray(Charsets.UTF_8))) {
            "Failed to generate AndroidKeyStore device key"
        }

        val enrollUrl = "$base/v1/device/enroll"
        val certChainJson = JSONArray().apply {
            DeviceKeyManager.attestationChain().forEach(::put)
        }
        val enrollPayload = JSONObject()
            .put("idToken", upstreamIdToken)
            .put("nonce", nonce)
            .put("certChain", certChainJson)
            .put("jwk", DpopSigner.jwk())
            .put("osPatchLevel", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "")
            .toString()

        val enrollReq = Request.Builder()
            .url(enrollUrl)
            .header("DPoP", DpopSigner.proof("POST", enrollUrl, null))
            .post(enrollPayload.toRequestBody(JSON_MEDIA))
            .build()

        val enrolledId = client.newCall(enrollReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Enrollment failed: HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            json.getString("deviceId")
        }
        cachedDeviceId = enrolledId
        return enrolledId
    }

    private fun exchangeSession(
        client: OkHttpClient,
        base: String,
        upstreamIdToken: String,
        deviceId: String,
    ): Pair<String, Long> {
        val exchangeUrl = "$base/v1/session/exchange"
        val body = JSONObject()
            .put("idToken", upstreamIdToken)
            .put("deviceId", deviceId)
            .toString()

        val req = Request.Builder()
            .url(exchangeUrl)
            .header("DPoP", DpopSigner.proof("POST", exchangeUrl, null))
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Session exchange failed: HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val token = json.getString("accessToken")
            val expiresInSec = json.optLong("expiresIn", 600L).coerceAtLeast(60L)
            token to (nowMs() + expiresInSec * 1000L)
        }
    }

    fun clear() {
        cachedToken = null
        cachedDeviceId = null
        expiresAtMs = 0L
    }

    internal fun setCachedForTest(token: String?, deviceId: String?, expiresAtEpochMs: Long, client: OkHttpClient? = null) {
        cachedToken = token
        cachedDeviceId = deviceId
        expiresAtMs = expiresAtEpochMs
        bootstrapClientOverride = client
    }
}
