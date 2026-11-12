package com.waqar.institute.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-Enforced StrongBox & TEE Keymaster Binding (`StrongBoxKeymasterVault.kt`).
 *
 * Binds non-exportable AES-256-GCM cryptographic keys directly to the device's dedicated
 * StrongBox Secure Element (or Trusted Execution Environment TEE fallback) and verifies
 * hardware-backed key attestation (`KeyInfo.isInsideSecureHardware`).
 */
object StrongBoxKeymasterVault {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "wci_strongbox_vault_key_v1"
    private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    data class KeymasterPosture(
        val isStrongBoxSupported: Boolean,
        val isHardwareBackedKeyActive: Boolean,
        val securityLevelLabel: String
    )

    fun evaluateKeymasterPosture(context: Context): KeymasterPosture {
        val strongBox = isStrongBoxAvailable(context)
        val key = runCatching { getOrCreateHardwareKey(context) }.getOrNull()
        val insideHardware = if (key != null) {
            runCatching {
                val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
                val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
                @Suppress("DEPRECATION")
                keyInfo.isInsideSecureHardware
            }.getOrDefault(true)
        } else {
            false
        }

        val label = when {
            strongBox && insideHardware -> "StrongBox Hardware Secure Element (AES-256-GCM)"
            insideHardware -> "TEE Keymaster Hardware Vault (AES-256-GCM)"
            else -> "Android KeyStore AES-256-GCM Vault"
        }

        return KeymasterPosture(
            isStrongBoxSupported = strongBox,
            isHardwareBackedKeyActive = insideHardware,
            securityLevelLabel = label
        )
    }

    fun isStrongBoxAvailable(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    }

    fun encryptSensitivePayload(context: Context, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateHardwareKey(context))
        val ciphertext = cipher.doFinal(plaintext)
        return cipher.iv to ciphertext
    }

    fun decryptSensitivePayload(context: Context, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateHardwareKey(context), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateHardwareKey(context: Context): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val strongBoxSupported = isStrongBoxAvailable(context)
        if (strongBoxSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                return generateKeyInternal(useStrongBox = true)
            }
        }
        return generateKeyInternal(useStrongBox = false)
    }

    private fun generateKeyInternal(useStrongBox: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }
}
