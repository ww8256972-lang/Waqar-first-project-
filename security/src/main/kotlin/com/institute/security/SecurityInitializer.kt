package com.institute.security

import android.content.Context
import android.util.Base64
import androidx.startup.Initializer

class SecurityInitializer : Initializer<SecurityKit> {
    override fun create(context: Context): SecurityKit {
        val pins = BuildConfig.TLS_PINS
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        SecurityKit.init(
            ctx = context.applicationContext,
            cfg = SecurityConfig(
                gatewayBaseUrl = BuildConfig.GATEWAY_URL,
                tlsPins = pins,
                cloudProjectNumber = BuildConfig.PLAY_CLOUD_PROJECT.toLongOrNull() ?: 0L,
                pqcKid = BuildConfig.PQC_KID,
                pqcMlKemPub = safeDecodeBase64(BuildConfig.PQC_MLKEM_PUB),
                pqcX25519Pub = safeDecodeBase64(BuildConfig.PQC_X25519_PUB),
            ),
        )
        return SecurityKit
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private fun safeDecodeBase64(raw: String): ByteArray {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("<")) return ByteArray(0)
        return runCatching { Base64.decode(trimmed, Base64.NO_WRAP) }.getOrDefault(ByteArray(0))
    }
}
