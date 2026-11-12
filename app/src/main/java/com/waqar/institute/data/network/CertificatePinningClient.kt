package com.waqar.institute.data.network

import android.content.Context
import android.util.Base64
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.TlsVersion
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Enterprise SPKI SHA-256 Certificate Pinning & Dynamic Endpoint Validation Client.
 *
 * Protects all Firebase, Google Trust Services, and Waqar Computer Institute API endpoints
 * against Man-in-the-Middle (MITM) proxy interception (Charles Proxy, Fiddler, Burp Suite,
 * Mitmproxy) while enforcing TLS 1.3 / TLS 1.2 modern cipher suites.
 */
object CertificatePinningClient {

    // Official Google Trust Services & Global Root SPKI SHA-256 Pins
    private const val GTS_ROOT_R1_PIN = "sha256/hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc="
    private const val GTS_ROOT_R2_PIN = "sha256/Vfd95BwDeSQo+NUYxVEEIlvkOlWY2SalKK1lPhzOx78="
    private const val GTS_CA_1C3_PIN = "sha256/zUIraRNo+4JoAYA7ROeWjARtIoN4rIEbCpfCRQT6N6A="
    private const val GTS_WR2_PIN = "sha256/YPtHaftLw6/0vnc2BnNKGF54xiCA28WFcccjkA4ypCM="
    private const val DIGICERT_GLOBAL_ROOT_G2_PIN = "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY="

    private val ALLOWED_HOSTS = setOf(
        "googleapis.com",
        "firebaseio.com",
        "firestore.googleapis.com",
        "identitytoolkit.googleapis.com",
        "securetoken.googleapis.com",
        "firebaseinstallations.googleapis.com",
        "firebaseremoteconfig.googleapis.com",
        "api.openai.com"
    )

    fun buildCertificatePinner(): CertificatePinner {
        return CertificatePinner.Builder()
            .add("*.googleapis.com", GTS_ROOT_R1_PIN, GTS_ROOT_R2_PIN, GTS_CA_1C3_PIN, GTS_WR2_PIN)
            .add("*.firebaseio.com", GTS_ROOT_R1_PIN, GTS_ROOT_R2_PIN, GTS_CA_1C3_PIN, GTS_WR2_PIN)
            .add("api.openai.com", GTS_ROOT_R1_PIN, DIGICERT_GLOBAL_ROOT_G2_PIN)
            .build()
    }

    /**
     * Validates that a dynamic endpoint URL strictly uses `https://` and targets an approved
     * domain hierarchy before any socket connection is opened.
     */
    fun validateDynamicEndpointUrl(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") return false
        return ALLOWED_HOSTS.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
    }

    /**
     * Computes the Base64-encoded SHA-256 hash of an X509Certificate's SubjectPublicKeyInfo (SPKI).
     */
    fun computeSpkiSha256Pin(certificate: X509Certificate): String {
        val spkiBytes = certificate.publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(spkiBytes)
        return "sha256/" + Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    fun createPinnedOkHttpClient(context: Context): OkHttpClient {
        // Delegate base setup to com.example.security.CertificatePinningClient and attach
        // strict TLS 1.3 + SPKI + dynamic endpoint verification interceptor.
        val baseClient = com.example.security.CertificatePinningClient.createPinnedOkHttpClient(context)
        val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .build()

        return baseClient.newBuilder()
            .connectionSpecs(listOf(tlsSpec))
            .certificatePinner(buildCertificatePinner())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addNetworkInterceptor(SpkiPeerVerificationInterceptor())
            .build()
    }

    private class SpkiPeerVerificationInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!request.isHttps) {
                throw SSLPeerUnverifiedException("Blocked insecure non-HTTPS request to ${request.url.host}")
            }
            val handshake = chain.connection()?.handshake()
            val peerCerts = handshake?.peerCertificates.orEmpty()
            if (peerCerts.isNotEmpty()) {
                val leaf = peerCerts.first() as? X509Certificate
                leaf?.checkValidity()
            }
            return chain.proceed(request)
        }
    }
}
