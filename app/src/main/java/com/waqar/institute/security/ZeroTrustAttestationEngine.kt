package com.waqar.institute.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.Keep
import org.lsposed.lsparanoid.Obfuscate
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Hardware-Backed Zero-Trust Attestation & Ephemeral Signed Token Engine (`ZeroTrustAttestationEngine`).
 *
 * Uses AndroidKeyStore Hardware-Backed EC (`secp256r1`) keys with `setAttestationChallenge`
 * and `KeyStore.getCertificateChain()` (gracefully falling back across StrongBox -> TEE KeyStore ->
 * OEM software EC if a student phone lacks StrongBox/Attestation support, avoiding startup crashes).
 *
 * Produces 60-second TTL ECDSA (`SHA256withECDSA`) tokens binding `userId`, `portalRole`,
 * `timestampWindow`, and `requestNonce`, paired with C++20 VM verification.
 */
@Obfuscate
@Keep
object ZeroTrustAttestationEngine {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "wci_zero_trust_ec_attest_v1"
    const val EPHEMERAL_WINDOW_MS = 60_000L

    private var cachedSoftwareKeyPair: KeyPair? = null

    data class AttestationToken(
        val userId: String,
        val portalRole: String,
        val timestampWindow: Long,
        val requestNonce: String,
        val payloadString: String,
        val signatureBase64: String,
        val hardwareBacked: Boolean,
        val vmVerified: Boolean,
        val publicKeyFingerprint: String = "",
        val attestationChainLength: Int = 0
    )

    private fun sha256Hex(bytes: ByteArray): String = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun computePublicKeyFingerprint(publicKey: PublicKey): String =
        sha256Hex(publicKey.encoded)

    private fun readAttestationChainSummary(): Pair<Int, String> = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val chain = ks.getCertificateChain(KEY_ALIAS)
        if (chain != null && chain.isNotEmpty()) {
            chain.size to sha256Hex(chain[0].encoded)
        } else {
            0 to ""
        }
    }.getOrDefault(0 to "")

    private fun isKeyInsideSecureHardware(privateKey: PrivateKey): Boolean = runCatching {
        val factory = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
        val keyInfo = factory.getKeySpec(privateKey, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            @Suppress("DEPRECATION")
            keyInfo.isInsideSecureHardware
        }
    }.getOrDefault(false)

    private fun deleteKeyStoreEntrySafely() {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS)
            }
        }
    }

    @Synchronized
    private fun getOrCreateSigningKeyPair(
        nonce: ByteArray,
        forceRegenerate: Boolean = false
    ): Pair<KeyPair, Boolean> {
        if (forceRegenerate) {
            deleteKeyStoreEntrySafely()
        } else {
            // 1. Reuse existing AndroidKeyStore EC key if present
            runCatching {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                if (entry != null) {
                    val keyPair = KeyPair(entry.certificate.publicKey, entry.privateKey)
                    return keyPair to isKeyInsideSecureHardware(entry.privateKey)
                }
            }
        }

        // 2. Try StrongBox hardware attestation first on API 28+, then TEE KeyStore, then OEM software EC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val kp = generateKeyStoreEcPair(nonce, useStrongBox = true, useChallenge = true)
                return kp to isKeyInsideSecureHardware(kp.private)
            }
        }

        runCatching {
            val kp = generateKeyStoreEcPair(nonce, useStrongBox = false, useChallenge = true)
            return kp to isKeyInsideSecureHardware(kp.private)
        }

        runCatching {
            val kp = generateKeyStoreEcPair(nonce, useStrongBox = false, useChallenge = false)
            return kp to isKeyInsideSecureHardware(kp.private)
        }

        // 3. Last-resort pure software EC secp256r1 keypair (never crashes legacy/budget OEM devices or host JVM)
        return getOrCreateSoftwareEcPair() to false
    }

    @Synchronized
    private fun getOrCreateSoftwareEcPair(): KeyPair {
        val existingSw = cachedSoftwareKeyPair
        if (existingSw != null) return existingSw
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair().also { cachedSoftwareKeyPair = it }
    }

    private fun generateKeyStoreEcPair(
        nonce: ByteArray,
        useStrongBox: Boolean,
        useChallenge: Boolean
    ): KeyPair {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)

        if (useChallenge && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setAttestationChallenge(nonce)
        }
        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        kpg.initialize(builder.build())
        return kpg.generateKeyPair()
    }

    private fun signPayloadWithKey(privateKey: PrivateKey, payload: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(payload)
        return sig.sign()
    }

    /**
     * Generates a short-lived (60-second TTL) hardware-bound ECDSA token returning
     * the signed token, public key fingerprint, and timestamp metadata.
     */
    fun generateHardwareBoundToken(
        userId: String,
        portalRole: String
    ): Map<String, String> {
        val token = generateHardwareBoundToken(context = null, userId = userId, portalRole = portalRole)
        val issuedAtMillis = System.currentTimeMillis()
        val expiresAtMillis = (token.timestampWindow + 1L) * EPHEMERAL_WINDOW_MS
        return mapOf(
            "signedToken" to "${token.payloadString}.${token.signatureBase64}",
            "payload" to token.payloadString,
            "signature" to token.signatureBase64,
            "publicKeyFingerprint" to token.publicKeyFingerprint,
            "timestamp" to issuedAtMillis.toString(),
            "timestampWindow" to token.timestampWindow.toString(),
            "expiresAtMillis" to expiresAtMillis.toString(),
            "requestNonce" to token.requestNonce,
            "hardwareBacked" to token.hardwareBacked.toString(),
            "attestationChainLength" to token.attestationChainLength.toString(),
            "vmVerified" to token.vmVerified.toString()
        )
    }

    /**
     * Issues a 60-second TTL hardware-bound ECDSA attestation token and verifies the session payload
     * inside the native C++20 VM interpreter.
     */
    fun generateHardwareBoundToken(
        context: Context?,
        userId: String,
        portalRole: String
    ): AttestationToken {
        val cleanUser = userId.trim().ifBlank { "wci_user" }
        val cleanRole = portalRole.trim().ifBlank { "student" }
        val nonceBytes = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val requestNonce = nonceBytes.joinToString("") { "%02x".format(it) }
        val timestampWindow = System.currentTimeMillis() / EPHEMERAL_WINDOW_MS
        val payloadString = "$cleanUser:$cleanRole:$timestampWindow:$requestNonce"
        val payloadBytes = payloadString.toByteArray(Charsets.UTF_8)

        var (keyPair, isHwBacked) = runCatching {
            getOrCreateSigningKeyPair(nonceBytes, forceRegenerate = false)
        }.getOrElse {
            getOrCreateSoftwareEcPair() to false
        }

        val signatureBytes = runCatching {
            signPayloadWithKey(keyPair.private, payloadBytes)
        }.recoverCatching {
            // Recover automatically if existing AndroidKeyStore key was invalidated (e.g. lockscreen reset)
            val regenerated = getOrCreateSigningKeyPair(nonceBytes, forceRegenerate = true)
            keyPair = regenerated.first
            isHwBacked = regenerated.second
            signPayloadWithKey(keyPair.private, payloadBytes)
        }.getOrElse {
            val swPair = getOrCreateSoftwareEcPair()
            keyPair = swPair
            isHwBacked = false
            signPayloadWithKey(swPair.private, payloadBytes)
        }

        val (chainLength, _) = readAttestationChainSummary()
        val pubFingerprint = computePublicKeyFingerprint(keyPair.public)
        val sigB64 = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        val vmOk = BinaryVirtualizationBridge.verifySessionBinding(cleanUser, cleanRole, requestNonce)

        return AttestationToken(
            userId = cleanUser,
            portalRole = cleanRole,
            timestampWindow = timestampWindow,
            requestNonce = requestNonce,
            payloadString = payloadString,
            signatureBase64 = sigB64,
            hardwareBacked = isHwBacked,
            vmVerified = vmOk,
            publicKeyFingerprint = pubFingerprint,
            attestationChainLength = chainLength
        )
    }

    fun isTokenWindowValid(token: AttestationToken): Boolean {
        val currentWindow = System.currentTimeMillis() / EPHEMERAL_WINDOW_MS
        return kotlin.math.abs(currentWindow - token.timestampWindow) <= 1L && token.vmVerified
    }

    fun verifyTokenSignature(token: AttestationToken): Boolean {
        if (!isTokenWindowValid(token) || token.signatureBase64.isBlank()) return false
        val signatureBytes = runCatching {
            Base64.decode(token.signatureBase64, Base64.NO_WRAP)
        }.getOrNull() ?: return false
        val payloadBytes = token.payloadString.toByteArray(Charsets.UTF_8)

        val keyStorePublicKey = runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.certificate?.publicKey
        }.getOrNull()

        val candidateKeys = listOfNotNull(keyStorePublicKey, cachedSoftwareKeyPair?.public)
        return candidateKeys.any { pubKey ->
            runCatching {
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(pubKey)
                verifier.update(payloadBytes)
                verifier.verify(signatureBytes)
            }.getOrDefault(false)
        }
    }
}
