package com.institute.security.device

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

object DeviceKeyManager {
    private const val ALIAS = "inst_device_key_v1"

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun hasKey(): Boolean = runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)

    /**
     * Generates a device-bound P-256 signing keypair with server-provided attestation challenge
     * if one does not already exist. Tries StrongBox on API 28+ and falls back to TEE/Keystore
     * so devices without StrongBox never crash.
     */
    fun ensureKey(attestationChallenge: ByteArray): Boolean {
        val ks = runCatching { keyStore() }.getOrNull() ?: return false
        if (runCatching { ks.containsAlias(ALIAS) }.getOrDefault(false)) return true

        fun buildSpec(strongBox: Boolean): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(attestationChallenge)
                .apply {
                    if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setIsStrongBoxBacked(true)
                    }
                }
                .build()

        val kpg = runCatching {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        }.getOrNull() ?: return false

        return try {
            kpg.initialize(buildSpec(strongBox = true))
            kpg.generateKeyPair()
            true
        } catch (_: Throwable) {
            runCatching {
                kpg.initialize(buildSpec(strongBox = false))
                kpg.generateKeyPair()
                true
            }.getOrDefault(false)
        }
    }

    fun attestationChain(): List<String> = runCatching {
        keyStore().getCertificateChain(ALIAS)
            ?.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun publicKey(): ECPublicKey =
        keyStore().getCertificate(ALIAS).publicKey as ECPublicKey

    fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyStore().getKey(ALIAS, null) as PrivateKey)
        update(data)
        sign() // ASN.1 DER format
    }

    fun clearKey() {
        runCatching {
            val ks = keyStore()
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        }
    }
}
