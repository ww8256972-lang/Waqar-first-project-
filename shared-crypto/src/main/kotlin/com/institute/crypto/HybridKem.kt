package com.institute.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Layer 4 Post-Quantum Hybrid KEM (NIST FIPS 203 ML-KEM-768 + X25519 + HKDF-SHA256 + AES-256-GCM).
 * Pure-Kotlin/BouncyCastle module shared across Android (:security) and server (security-gateway).
 */
object HybridKem {
    private const val INFO = "institute-hybrid-kem-v1"
    private val rng = SecureRandom()

    class RecipientPublic(
        val kid: String,
        val mlkem: ByteArray,
        val x25519: ByteArray,
    )

    class RecipientPrivate(
        val kid: String,
        val mlkem: ByteArray,
        val x25519: ByteArray,
    )

    class Sealed(
        val kid: String,
        val kemCt: ByteArray,
        val ephX25519: ByteArray,
        val nonce: ByteArray,
        val ct: ByteArray,
    )

    /** Generates long-term ML-KEM-768 + X25519 recipient keypair for KeyTool / server keyring. */
    fun generateRecipient(kid: String): Pair<RecipientPublic, RecipientPrivate> {
        val g = MLKEMKeyPairGenerator().apply {
            init(MLKEMKeyGenerationParameters(rng, MLKEMParameters.ml_kem_768))
        }
        val kp = g.generateKeyPair()
        val xPriv = X25519PrivateKeyParameters(rng)
        return Pair(
            RecipientPublic(
                kid = kid,
                mlkem = (kp.public as MLKEMPublicKeyParameters).encoded,
                x25519 = xPriv.generatePublicKey().encoded,
            ),
            RecipientPrivate(
                kid = kid,
                mlkem = (kp.private as MLKEMPrivateKeyParameters).encoded,
                x25519 = xPriv.encoded,
            ),
        )
    }

    /**
     * Seals [plain] to [to] binding [aad] (e.g. "action|objectId|ts|nonce") against tampering/replay.
     */
    fun seal(plain: ByteArray, aad: ByteArray, to: RecipientPublic): Sealed {
        require(to.mlkem.isNotEmpty() && to.x25519.size == 32) { "Invalid recipient public keys" }
        val enc = MLKEMGenerator(rng).generateEncapsulated(
            MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, to.mlkem),
        )
        val eph = X25519PrivateKeyParameters(rng)
        val ephPub = eph.generatePublicKey().encoded
        val ssX = ByteArray(32)
        X25519Agreement().apply { init(eph) }
            .calculateAgreement(X25519PublicKeyParameters(to.x25519, 0), ssX, 0)

        val key = derive(enc.secret, ssX, to.kid, enc.encapsulation, ephPub)
        val nonce = ByteArray(12).also(rng::nextBytes)
        return Sealed(
            kid = to.kid,
            kemCt = enc.encapsulation,
            ephX25519 = ephPub,
            nonce = nonce,
            ct = gcm(Cipher.ENCRYPT_MODE, key, nonce, aad, plain),
        )
    }

    /** Decrypts and authenticates [s] using [me] and expected [aad]. Throws on any tamper or key mismatch. */
    fun open(s: Sealed, aad: ByteArray, me: RecipientPrivate): ByteArray {
        require(s.kid == me.kid) { "Key ID mismatch: envelope=${s.kid}, key=${me.kid}" }
        require(s.nonce.size == 12) { "Invalid GCM nonce length" }
        require(s.ephX25519.size == 32) { "Invalid ephemeral X25519 public key length" }

        val ssKem = MLKEMExtractor(MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, me.mlkem))
            .extractSecret(s.kemCt)
        val ssX = ByteArray(32)
        X25519Agreement().apply { init(X25519PrivateKeyParameters(me.x25519, 0)) }
            .calculateAgreement(X25519PublicKeyParameters(s.ephX25519, 0), ssX, 0)

        val key = derive(ssKem, ssX, s.kid, s.kemCt, s.ephX25519)
        return gcm(Cipher.DECRYPT_MODE, key, s.nonce, aad, s.ct)
    }

    // Bind both shared secrets + ciphertext transcript in HKDF-SHA256 so breaking either primitive fails
    private fun derive(
        ssKem: ByteArray,
        ssX: ByteArray,
        kid: String,
        kemCt: ByteArray,
        ephPub: ByteArray,
    ): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256").digest(kemCt + ephPub)
        val hkdf = HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(ssKem + ssX, salt, "$INFO|$kid".toByteArray(Charsets.UTF_8)))
        }
        return ByteArray(32).also { hkdf.generateBytes(it, 0, 32) }
    }

    private fun gcm(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        data: ByteArray,
    ): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(aad)
        doFinal(data)
    }
}
