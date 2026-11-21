package com.example.security

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Role-Based Session Fingerprint & Token Replay Protection (`SessionFingerprintManager.kt`).
 *
 * Binds every authenticated portal session (`ADMIN`, `TEACHER`, `STUDENT`, `PARENT`) to a
 * deterministic hardware/device fingerprint (`ANDROID_ID` + `Build.BOARD` + `Build.MODEL`
 * + `Keystore Installation Key`). If a session token or credential payload is copied to another
 * device or replayed with an altered role claim, the HMAC-SHA256 fingerprint check rejects it.
 */
data class BoundSessionEnvelope(
    val uid: String,
    val username: String,
    val roleKey: String,
    val linkedEntityId: String,
    val deviceFingerprintHash: String,
    val issuedAtEpochMs: Long,
    val hmacSeal: String
)

object SessionFingerprintManager {
    private const val SEAL_ALGO = "HmacSHA256"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val HMAC_KEY_ALIAS = "wci_session_envelope_hmac_v2"
    private const val FALLBACK_SECRET_PREF_KEY = "wci_session_install_hmac_seed_v2"

    @Volatile
    private var inMemoryFallbackKey: SecretKey? = null

    @SuppressLint("HardwareIds")
    fun computeDeviceHardwareFingerprint(context: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrDefault("unknown_android_id")

        val rawSpec = buildString {
            append(androidId)
            append("|")
            append(Build.MANUFACTURER)
            append("|")
            append(Build.MODEL)
            append("|")
            append(Build.BOARD)
            append("|")
            append(Build.VERSION.SDK_INT)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(rawSpec.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun captureCurrentDeviceFingerprint(context: Context): String =
        computeDeviceHardwareFingerprint(context)

    /**
     * Creates a cryptographically sealed `BoundSessionEnvelope` tied to the current device's
     * hardware fingerprint and user role.
     */
    fun issueBoundSessionEnvelope(
        context: Context,
        uid: String,
        username: String,
        roleKey: String,
        linkedEntityId: String
    ): BoundSessionEnvelope {
        val deviceFp = computeDeviceHardwareFingerprint(context)
        val nowMs = System.currentTimeMillis()
        val seal = computeEnvelopeHmac(context, uid, username, roleKey, linkedEntityId, deviceFp, nowMs)
        return BoundSessionEnvelope(
            uid = uid,
            username = username,
            roleKey = roleKey,
            linkedEntityId = linkedEntityId,
            deviceFingerprintHash = deviceFp,
            issuedAtEpochMs = nowMs,
            hmacSeal = seal
        )
    }

    /**
     * Verifies that the session envelope has not been tampered with, replayed on a different
     * physical device, or escalated to another role (e.g., `student` -> `admin`).
     */
    fun verifySessionEnvelope(
        context: Context,
        envelope: BoundSessionEnvelope,
        expectedRoleKey: String
    ): Boolean {
        if (!envelope.roleKey.equals(expectedRoleKey, ignoreCase = true)) return false
        val currentDeviceFp = computeDeviceHardwareFingerprint(context)
        if (!MessageDigest.isEqual(
                currentDeviceFp.toByteArray(Charsets.UTF_8),
                envelope.deviceFingerprintHash.toByteArray(Charsets.UTF_8)
            )
        ) {
            return false
        }
        val expectedSeal = computeEnvelopeHmac(
            context = context,
            uid = envelope.uid,
            username = envelope.username,
            roleKey = envelope.roleKey,
            linkedEntityId = envelope.linkedEntityId,
            deviceFp = envelope.deviceFingerprintHash,
            issuedAtEpochMs = envelope.issuedAtEpochMs
        )
        return MessageDigest.isEqual(
            expectedSeal.toByteArray(Charsets.UTF_8),
            envelope.hmacSeal.toByteArray(Charsets.UTF_8)
        )
    }

    private fun getOrCreateSigningKey(context: Context): SecretKey {
        // 1. Prefer AndroidKeyStore non-exportable HMAC-SHA256 key on physical/emulator Android devices
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getEntry(HMAC_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let {
                return it
            }
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            keyGen.init(
                KeyGenParameterSpec.Builder(HMAC_KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(256)
                    .build()
            )
            return keyGen.generateKey()
        }

        // 2. EncryptedPreferences-backed installation key fallback (for Robolectric/JVM tests only)
        inMemoryFallbackKey?.let { return it }
        synchronized(this) {
            inMemoryFallbackKey?.let { return it }
            val prefs = HardwareKeystoreVault.openEncryptedPreferences(context.applicationContext ?: context)
            val existingB64 = prefs.getString(FALLBACK_SECRET_PREF_KEY, null)
            val keyBytes = if (!existingB64.isNullOrBlank()) {
                Base64.decode(existingB64, Base64.NO_WRAP)
            } else {
                ByteArray(32).also {
                    SecureRandom().nextBytes(it)
                    prefs.edit().putString(
                        FALLBACK_SECRET_PREF_KEY,
                        Base64.encodeToString(it, Base64.NO_WRAP)
                    ).apply()
                }
            }
            return SecretKeySpec(keyBytes, SEAL_ALGO).also { inMemoryFallbackKey = it }
        }
    }

    private fun computeEnvelopeHmac(
        context: Context,
        uid: String,
        username: String,
        roleKey: String,
        linkedEntityId: String,
        deviceFp: String,
        issuedAtEpochMs: Long
    ): String {
        val payload = "$uid|$username|${roleKey.lowercase()}|$linkedEntityId|$deviceFp|$issuedAtEpochMs"
        val mac = Mac.getInstance(SEAL_ALGO)
        mac.init(getOrCreateSigningKey(context))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
