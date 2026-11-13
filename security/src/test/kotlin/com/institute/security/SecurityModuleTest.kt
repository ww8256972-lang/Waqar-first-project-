package com.institute.security

import com.institute.security.authz.AuthzClient
import com.institute.security.device.DpopSigner
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class SecurityModuleTest {

    @Test
    fun dpopProof_containsRequiredHeadersClaimsAndValid64ByteSignature() {
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val kp = kpg.generateKeyPair()
        val ecPub = kp.public as ECPublicKey

        val proof = DpopSigner.proof(
            method = "POST",
            url = "https://secure.yourinstitute.in/v1/attendance/cpp2026/face",
            accessToken = "header.payload.sig",
            publicKey = ecPub,
            signer = { data ->
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(kp.private)
                    update(data)
                    sign()
                }
            },
            nowEpochSec = 1_760_000_000L,
            jti = "test-jti-123",
        )

        val parts = proof.split(".")
        assertEquals(3, parts.size)

        val header = JSONObject(String(Base64.getUrlDecoder().decode(parts[0])))
        assertEquals("dpop+jwt", header.getString("typ"))
        assertEquals("ES256", header.getString("alg"))
        val jwk = header.getJSONObject("jwk")
        assertEquals("EC", jwk.getString("kty"))
        assertEquals("P-256", jwk.getString("crv"))

        val payload = JSONObject(String(Base64.getUrlDecoder().decode(parts[1])))
        assertEquals("POST", payload.getString("htm"))
        assertEquals("https://secure.yourinstitute.in/v1/attendance/cpp2026/face", payload.getString("htu"))
        assertEquals(1_760_000_000L, payload.getLong("iat"))
        assertEquals("test-jti-123", payload.getString("jti"))
        assertTrue(payload.getString("ath").isNotBlank())

        val rawSig = Base64.getUrlDecoder().decode(parts[2])
        assertEquals(64, rawSig.size)

        val thumbprint = DpopSigner.jwkThumbprint(ecPub)
        assertNotEquals("", thumbprint)
        assertEquals(43, thumbprint.length) // base64url(SHA-256) without padding
    }

    @Test
    fun authzClient_cachesWithin60Seconds_andFailsClosedOnErrors() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"allowed":true}"""))
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))

            var now = 1_000L
            val cfg = SecurityConfig(
                gatewayBaseUrl = server.url("/").toString().trimEnd('/'),
                tlsPins = emptyList(),
                cloudProjectNumber = 0L,
                pqcKid = "k-2026-11",
                pqcMlKemPub = ByteArray(0),
                pqcX25519Pub = ByteArray(0),
            )
            val authz = AuthzClient(cfg, OkHttpClient(), clockMs = { now })

            // First call queries server -> true
            assertTrue(authz.can("can_mark_attendance", "batch:cpp2026"))
            assertEquals(1, server.requestCount)

            // Within 60s window -> served from cache without network call
            now += 30_000L
            assertTrue(authz.can("can_mark_attendance", "batch:cpp2026"))
            assertEquals(1, server.requestCount)

            // After 60s expiry + server 500 -> must fail closed (false)
            now += 35_000L
            assertFalse(authz.can("can_mark_attendance", "batch:cpp2026"))
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
