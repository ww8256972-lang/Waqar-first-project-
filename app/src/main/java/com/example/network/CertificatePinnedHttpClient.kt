package com.example.network

import android.content.Context
import com.appmattus.certificatetransparency.certificateTransparencyInterceptor
import com.example.security.CertificatePinningClient
import okhttp3.OkHttpClient
import org.lsposed.lsparanoid.Obfuscate
import java.util.concurrent.TimeUnit

/**
 * Certificate-Pinned & Certificate-Transparency (CT) Hardened OkHttp Client (`CertificatePinnedHttpClient`).
 *
 * Attaches `com.appmattus.certificatetransparency.certificateTransparencyInterceptor` alongside
 * SPKI SHA-256 root/intermediate pins to block rogue Magisk system CA MITM proxies (Burp Suite,
 * Charles Proxy, HttpCanary, Fiddler).
 */
@Obfuscate
object CertificatePinnedHttpClient {

    fun buildPinnedOkHttpClient(context: Context? = null): OkHttpClient {
        val ctInterceptor = certificateTransparencyInterceptor {
            +"*.googleapis.com"
            +"*.firebaseio.com"
            failOnError = true
        }

        val baseBuilder = if (context != null) {
            CertificatePinningClient.createPinnedOkHttpClient(context).newBuilder()
        } else {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
        }

        return baseBuilder
            .addNetworkInterceptor(ctInterceptor)
            .build()
    }
}
