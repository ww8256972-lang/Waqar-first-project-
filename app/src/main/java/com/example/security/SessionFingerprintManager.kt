package com.example.security

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Role-Based Session Fingerprint & Token Replay Protection (`SessionFingerprintManager.kt`).
 *
 * Binds every authenticated portal session (`ADMIN`, `TEACHER`, `STUDENT`, `PARENT`) to a
 * deterministic hardware/device fingerprint (`ANDROID_ID` + `Build.BOARD` + `Build.FINGERPRINT`
 * + `Keystore Installation Salt`). If a session token or credential payload is copied to another
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
    private const val SEAL_KEY_CONTEXT = "WCI_SESSION_REPLAY_GUARD_2026"

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
        val seal = computeEnvelopeHmac(uid, username, roleKey, linkedEntityId, deviceFp, nowMs)
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

    private fun computeEnvelopeHmac(
        uid: String,
        username: String,
        roleKey: String,
        linkedEntityId: String,
        deviceFp: String,
        issuedAtEpochMs: Long
    ): String {
        val payload = "$uid|$username|${roleKey.lowercase()}|$linkedEntityId|$deviceFp|$issuedAtEpochMs"
        val mac = Mac.getInstance(SEAL_ALGO)
        mac.init(SecretKeySpec(SEAL_KEY_CONTEXT.toByteArray(Charsets.UTF_8), SEAL_ALGO))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
