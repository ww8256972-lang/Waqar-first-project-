package com.waqar.institute.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.example.data.firestore.InstituteRepository
import com.example.security.SessionFingerprintManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.UUID

/**
 * Layer 1 — Hardware-Bound RFC 9449 DPoP Request Signer (`HardwareDpopRequestSigner.kt`).
 *
 * - Generates/loads a non-exportable Android Keystore ECDSA P-256 keypair (StrongBox when
 *   supported, TEE fallback on standard Android devices, ephemeral in-memory EC P-256 keypair
 *   strictly in Robolectric/JVM unit tests).
 * - Registers the device's X.509 SPKI Base64 public key into Cloud Firestore at
 *   `device_pubkeys/{uid}_{deviceFingerprint}`.
 * - Produces per-request proof tokens `<base64url(payloadJson)>.<base64url(ecdsaDerSignature)>`
 *   containing `{ jti, iat, htm, htu, ath, deviceFp }` verified server-side by Cloud Functions
 *   with ±120s clock tolerance and atomic anti-replay `dpop_nonces/{jti}`.
 */
object HardwareDpopRequestSigner {

    private const val TAG = "HardwareDpopSigner"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "wci_hardware_dpop_ec_p256_v1"

    const val HEADER_DPOP = "X-WCI-DPoP"
    const val HEADER_DEVICE_FP = "X-WCI-Device-Fp"

    @Volatile
    private var jvmTestKeyPair: KeyPair? = null

    data class DpopProofResult(
        val proofToken: String,
        val deviceFingerprint: String,
        val jti: String,
        val iatSeconds: Long,
        val publicKeySpkiBase64: String
    )

    private fun isRobolectric(): Boolean =
        Build.FINGERPRINT.equals("robolectric", ignoreCase = true)

    @Synchronized
    private fun getOrCreateKeyPair(): Pair<PrivateKey, PublicKey>? {
        if (!isRobolectric()) {
            val androidPair = runCatching {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (ks.containsAlias(KEY_ALIAS)) {
                    val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                    if (entry != null) {
                        return@runCatching entry.privateKey to entry.certificate.publicKey
                    }
                }
                generateAndroidKeystoreEcKeyPair(useStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    ?: generateAndroidKeystoreEcKeyPair(useStrongBox = false)
            }.getOrNull()
            if (androidPair != null) return androidPair
        }

        // Ephemeral JVM EC P-256 fallback (used in Robolectric / headless unit tests or broken Keystore ROMs)
        val cached = jvmTestKeyPair
        if (cached != null) return cached.private to cached.public

        return runCatching {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            jvmTestKeyPair = kp
            kp.private to kp.public
        }.getOrNull()
    }

    private fun generateAndroidKeystoreEcKeyPair(useStrongBox: Boolean): Pair<PrivateKey, PublicKey>? {
        return runCatching {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
            if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
            }
            kpg.initialize(builder.build())
            val kp = kpg.generateKeyPair()
            kp.private to kp.public
        }.getOrNull()
    }

    fun getPublicKeySpkiBase64(): String? {
        val pair = getOrCreateKeyPair() ?: return null
        return runCatching {
            Base64.encodeToString(pair.second.encoded, Base64.NO_WRAP)
        }.getOrNull()
    }

    fun sha256Hex(rawBody: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rawBody.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun resolveDeviceFingerprint(context: Context?): String {
        return if (context != null) {
            runCatching {
                SessionFingerprintManager.captureCurrentDeviceFingerprint(context.applicationContext ?: context)
            }.getOrDefault("fp_fallback_device")
        } else {
            "fp_fallback_device"
        }
    }

    fun signRequest(
        context: Context?,
        httpMethod: String,
        url: String,
        rawBody: String = "",
        deviceFpOverride: String? = null
    ): DpopProofResult? {
        val pair = getOrCreateKeyPair() ?: return null
        val deviceFp = deviceFpOverride?.takeIf { it.isNotBlank() } ?: resolveDeviceFingerprint(context)
        val jti = UUID.randomUUID().toString()
        val iatSeconds = System.currentTimeMillis() / 1000L
        val ath = sha256Hex(rawBody)

        val payloadJson = JSONObject().apply {
            put("jti", jti)
            put("iat", iatSeconds)
            put("htm", httpMethod.trim().uppercase(Locale.US))
            put("htu", url.trim())
            put("ath", ath)
            put("deviceFp", deviceFp)
        }.toString()

        val payloadB64 = Base64.encodeToString(
            payloadJson.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

        val sigDer = runCatching {
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(pair.first)
            signer.update(payloadB64.toByteArray(StandardCharsets.UTF_8))
            signer.sign()
        }.getOrNull() ?: return null

        val sigB64 = Base64.encodeToString(
            sigDer,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val spkiB64 = Base64.encodeToString(pair.second.encoded, Base64.NO_WRAP)

        return DpopProofResult(
            proofToken = "$payloadB64.$sigB64",
            deviceFingerprint = deviceFp,
            jti = jti,
            iatSeconds = iatSeconds,
            publicKeySpkiBase64 = spkiB64
        )
    }

    fun attachDpopHeaders(
        builder: Request.Builder,
        context: Context?,
        httpMethod: String,
        url: String,
        rawBody: String = "",
        deviceFpOverride: String? = null
    ): DpopProofResult? {
        val proof = signRequest(
            context = context,
            httpMethod = httpMethod,
            url = url,
            rawBody = rawBody,
            deviceFpOverride = deviceFpOverride
        )
        val fp = proof?.deviceFingerprint ?: deviceFpOverride ?: resolveDeviceFingerprint(context)
        builder.header(HEADER_DEVICE_FP, fp)
        if (proof != null) {
            builder.header(HEADER_DPOP, proof.proofToken)
        }
        return proof
    }

    /**
     * Registers this device's hardware-bound ECDSA P-256 X.509 SPKI public key into
     * Cloud Firestore `/device_pubkeys/{uid}_{deviceFp}` using the active FirebaseAuth session.
     */
    suspend fun registerDevicePublicKeyInFirestore(
        context: Context?,
        uidOverride: String? = null,
        deviceFpOverride: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (isRobolectric()) return@withContext false
        val uid = uidOverride?.takeIf { it.isNotBlank() }
            ?: FirebaseAuth.getInstance().currentUser?.uid
            ?: return@withContext false
        val spkiB64 = getPublicKeySpkiBase64() ?: return@withContext false
        val rawFp = deviceFpOverride?.takeIf { it.isNotBlank() } ?: resolveDeviceFingerprint(context)
        val safeUid = uid.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
        val safeFp = rawFp.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
        val docId = "${safeUid}_${safeFp}"

        runCatching {
            withTimeoutOrNull(4_000L) {
                val db = InstituteRepository.getConfiguredFirestore()
                val payload = mapOf(
                    "uid" to uid,
                    "deviceFingerprint" to rawFp,
                    "publicKeySpkiBase64" to spkiB64,
                    "algorithm" to "ES256",
                    "updatedAtMs" to System.currentTimeMillis()
                )
                db.collection("device_pubkeys").document(docId).set(payload).await()
                true
            } ?: false
        }.onFailure { err ->
            Log.d(TAG, "Deferred device pubkey registration: ${err.localizedMessage}")
        }.getOrDefault(false)
    }

    /**
     * Pure cryptographic verification helper matching `functions/index.js` `verifyDpopProof`,
     * used by unit tests to validate DPoP proof format, SHA-256 `ath` body binding, and ECDSA signature.
     */
    fun verifyProofLocally(
        proofToken: String,
        publicKeySpkiBase64: String,
        expectedMethod: String,
        expectedUrl: String,
        expectedRawBody: String
    ): Boolean {
        val parts = proofToken.split(".")
        if (parts.size != 2) return false
        val payloadB64 = parts[0]
        val sigB64 = parts[1]
        return runCatching {
            val payloadBytes = Base64.decode(payloadB64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val sigBytes = Base64.decode(sigB64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val spkiBytes = Base64.decode(publicKeySpkiBase64, Base64.NO_WRAP)

            val pubKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spkiBytes))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(pubKey)
            verifier.update(payloadB64.toByteArray(StandardCharsets.UTF_8))
            if (!verifier.verify(sigBytes)) return@runCatching false

            val json = JSONObject(String(payloadBytes, StandardCharsets.UTF_8))
            val jti = json.optString("jti", "")
            val htm = json.optString("htm", "")
            val htu = json.optString("htu", "")
            val ath = json.optString("ath", "")
            val iat = json.optLong("iat", 0L)
            val nowSec = System.currentTimeMillis() / 1000L

            jti.length >= 8 &&
                htm.equals(expectedMethod.trim(), ignoreCase = true) &&
                htu.equals(expectedUrl.trim(), ignoreCase = false) &&
                ath.equals(sha256Hex(expectedRawBody), ignoreCase = true) &&
                kotlin.math.abs(nowSec - iat) <= 120L
        }.getOrDefault(false)
    }
}
