package com.institute.security.net

import com.institute.security.SecurityConfig
import com.institute.security.device.DpopSigner
import kotlinx.coroutines.runBlocking
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.URI
import java.util.concurrent.TimeUnit

object SecureHttp {

    fun buildPinnedClient(cfg: SecurityConfig): OkHttpClient {
        val host = runCatching { URI(cfg.gatewayBaseUrl).host }.getOrNull()
        val pinnerBuilder = CertificatePinner.Builder()
        if (!host.isNullOrBlank()) {
            cfg.tlsPins
                .map { it.trim() }
                .filter { it.startsWith("sha256/") || it.startsWith("sha1/") }
                .forEach { pin ->
                    runCatching { pinnerBuilder.add(host, pin) }
                }
        }
        return OkHttpClient.Builder()
            .certificatePinner(pinnerBuilder.build())
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun build(cfg: SecurityConfig): OkHttpClient {
        return buildPinnedClient(cfg)
            .newBuilder()
            .addInterceptor(DpopInterceptor())
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

class DpopInterceptor(
    private val tokenProvider: suspend () -> String = { SessionManager.accessToken() },
    private val proofProvider: (method: String, url: String, accessToken: String?) -> String = { method, url, accessToken ->
        DpopSigner.proof(method, url, accessToken)
    },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val orig = chain.request()
        val path = orig.url.encodedPath
        val htu = orig.url.newBuilder().query(null).fragment(null).build().toString()

        if (path.startsWith("/v1/session/") || path.startsWith("/v1/device/")) {
            return chain.proceed(
                orig.newBuilder()
                    .header("DPoP", proofProvider(orig.method, htu, null))
                    .build(),
            )
        }

        val token = runBlocking { tokenProvider() }
        return chain.proceed(
            orig.newBuilder()
                .header("Authorization", "DPoP $token")
                .header("DPoP", proofProvider(orig.method, htu, token))
                .build(),
        )
    }
}
