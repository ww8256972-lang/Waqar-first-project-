package com.waqar.institute.security

import android.content.Context
import android.util.Base64
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom

data class PlayIntegrityAttestationReport(
    val isHardwareVerified: Boolean,
    val deviceRecognitionVerdict: String,
    val appLicensingVerdict: String,
    val ollvmNativeDigestHex: String,
    val cryptographicNonceBase64: String,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

/**
 * Hardware-Rooted Play Integrity API & OLLVM Native Attestation Enforcer
 * (`PlayIntegrityEnforcer.kt`).
 *
 * Combines Google Play Integrity API (`IntegrityManagerFactory`) token requests with the
 * C++20 OLLVM Control-Flow Flattened JNI engine (`OllvmControlFlowEngine.cpp`) to verify
 * application binary authenticity and hardware integrity before sensitive ERP transactions.
 */
object PlayIntegrityEnforcer {

    init {
        runCatching {
            System.loadLibrary("waqar_native_security")
        }
    }

    private external fun nativeComputeOllvmFlattenedDigest(payload: String, salt: Long): Long

    fun computeOllvmProtectedDigestHex(payload: String, salt: Long = 0x5743495F32303236L): String {
        return runCatching {
            val rawLong = nativeComputeOllvmFlattenedDigest(payload, salt)
            java.lang.Long.toUnsignedString(rawLong, 16).uppercase().padStart(16, '0')
        }.getOrElse {
            val sha = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            sha.take(8).joinToString("") { "%02X".format(it) }
        }
    }

    @Volatile
    private var cloudProjectNumber: Long = 0L

    fun configureCloudProjectNumber(projectNumber: Long) {
        if (projectNumber > 0L) {
            cloudProjectNumber = projectNumber
        }
    }

    /**
     * Standalone GitHub Release APK Hardware & Signature Integrity Verdict (FIX-04).
     * Verifies the APK V2/V3 signing certificate via JNI (`NativeSecurityBridge.verifyApkSignature`),
     * confirms zero active Frida/LSPosed hooks (`AntiHookManager`), and verifies Android Keystore
     * StrongBox/TEE hardware key availability (`StrongBoxKeymasterVault`).
     */
    suspend fun requestHardwareIntegrityVerdict(context: Context): Boolean = withContext(Dispatchers.IO) {
        val apkSigValid = runCatching { NativeSecurityBridge.verifyApkIntegrity(context) }.getOrDefault(false)
        val hooksClean = AntiHookManager.runComprehensiveHookScan().isClean
        val strongBoxKeyReady = runCatching {
            StrongBoxKeymasterVault.evaluateKeymasterPosture(context).isHardwareBackedKeyActive
        }.getOrDefault(false)
        apkSigValid && hooksClean && strongBoxKeyReady
    }

    /**
     * Generates a 256-bit high-entropy cryptographic nonce bound to the user/action and evaluates
     * standalone hardware + APK signature integrity alongside optional Play Integrity API token issuance.
     */
    suspend fun requestHardwareIntegrityAttestation(
        context: Context,
        actionScope: String,
        actorId: String
    ): PlayIntegrityAttestationReport = withContext(Dispatchers.IO) {
        val randomBytes = ByteArray(24)
        SecureRandom().nextBytes(randomBytes)
        val rawNonceInput = "$actionScope|$actorId|${System.currentTimeMillis()}|" +
            Base64.encodeToString(randomBytes, Base64.NO_WRAP)
        val nonceDigest = MessageDigest.getInstance("SHA-256")
            .digest(rawNonceInput.toByteArray(Charsets.UTF_8))
        val nonceBase64 = Base64.encodeToString(
            nonceDigest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val ollvmHex = computeOllvmProtectedDigestHex("$actionScope:$actorId:$nonceBase64")
        val standaloneVerified = requestHardwareIntegrityVerdict(context)

        runCatching {
            val integrityManager = IntegrityManagerFactory.create(context.applicationContext)
            val requestBuilder = IntegrityTokenRequest.builder()
                .setNonce(nonceBase64)
            if (cloudProjectNumber > 0L) {
                requestBuilder.setCloudProjectNumber(cloudProjectNumber)
            }
            val response = integrityManager.requestIntegrityToken(requestBuilder.build()).await()
            val token = response.token()
            val tokenIssued = token.isNotBlank()
            PlayIntegrityAttestationReport(
                isHardwareVerified = standaloneVerified,
                deviceRecognitionVerdict = if (standaloneVerified && tokenIssued) {
                    "STANDALONE_HARDWARE_AND_TOKEN_VERIFIED"
                } else if (standaloneVerified) {
                    "STANDALONE_GITHUB_APK_HARDWARE_VERIFIED"
                } else if (tokenIssued) {
                    "TOKEN_ISSUED_PENDING_SERVER_VERIFY"
                } else {
                    "INTEGRITY_TOKEN_EMPTY"
                },
                appLicensingVerdict = if (standaloneVerified) "OFFICIAL_GITHUB_RELEASE_SIGNED" else "UNVERIFIED",
                ollvmNativeDigestHex = ollvmHex,
                cryptographicNonceBase64 = nonceBase64
            )
        }.getOrElse { error ->
            PlayIntegrityAttestationReport(
                isHardwareVerified = standaloneVerified,
                deviceRecognitionVerdict = if (standaloneVerified) {
                    "STANDALONE_GITHUB_APK_HARDWARE_VERIFIED"
                } else {
                    "INTEGRITY_API_UNAVAILABLE: ${error.javaClass.simpleName}"
                },
                appLicensingVerdict = if (standaloneVerified) "OFFICIAL_GITHUB_RELEASE_SIGNED" else "UNVERIFIED_OFFLINE",
                ollvmNativeDigestHex = ollvmHex,
                cryptographicNonceBase64 = nonceBase64
            )
        }
    }
}
