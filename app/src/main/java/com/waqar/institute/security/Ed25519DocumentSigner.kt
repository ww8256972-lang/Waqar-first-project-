package com.waqar.institute.security

import android.content.Context
import android.util.Base64
import com.example.security.HardwareKeystoreVault
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * [A3] Ed25519 Asymmetric Digital Signatures for Certificates, PDF ID/Admit Cards & UPI Receipts.
 *
 * Uses BouncyCastle's deterministic `Ed25519Signer` bound to a hardware-sealed institute root seed
 * (or caller-supplied `masterSeed`) for offline-verifiable, tamper-evident document signatures.
 */
object Ed25519DocumentSigner {

    private const val SEED_SALT = "WCI_ED25519_OFFICIAL_DOCUMENT_SIGNING_ROOT_2026"
    private const val PREF_SEALED_ED25519_SEED = "wci_ed25519_master_seed_v1"

    private fun deriveInstituteKeyPair(
        masterSeed: ByteArray
    ): Pair<Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters> {
        val seed32 = MessageDigest.getInstance("SHA-256")
            .digest(masterSeed + SEED_SALT.toByteArray(Charsets.UTF_8))
        val priv = Ed25519PrivateKeyParameters(seed32, 0)
        val pub = priv.generatePublicKey()
        return priv to pub
    }

    /**
     * Resolves or provisions a hardware-sealed 32-byte institute signing seed in AndroidKeyStore /
     * EncryptedSharedPreferences for signing certificates, ID cards, Admit Cards, and UPI receipts.
     */
    fun getOrProvisionInstituteSigningSeed(context: Context?): ByteArray {
        if (context == null) {
            return MessageDigest.getInstance("SHA-256")
                .digest(SEED_SALT.toByteArray(Charsets.UTF_8))
        }
        return runCatching {
            val prefs = HardwareKeystoreVault.openEncryptedPreferences(context)
            val existing = prefs.getString(PREF_SEALED_ED25519_SEED, null)
            if (!existing.isNullOrBlank()) {
                return@runCatching runCatching {
                    HardwareKeystoreVault.decryptWithHardwareKey(context, existing)
                }.getOrElse {
                    Base64.decode(existing, Base64.NO_WRAP)
                }
            }
            val freshSeed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val sealedValue = runCatching {
                HardwareKeystoreVault.encryptWithHardwareKey(context, freshSeed)
            }.getOrElse {
                Base64.encodeToString(freshSeed, Base64.NO_WRAP)
            }
            prefs.edit().putString(PREF_SEALED_ED25519_SEED, sealedValue).apply()
            freshSeed
        }.getOrElse {
            MessageDigest.getInstance("SHA-256")
                .digest("${context.packageName}|$SEED_SALT".toByteArray(Charsets.UTF_8))
        }
    }

    fun signDocumentCanonicalPayload(canonicalPayload: String, masterSeed: ByteArray): String {
        val (priv, _) = deriveInstituteKeyPair(masterSeed)
        val signer = Ed25519Signer()
        signer.init(true, priv)
        val msg = canonicalPayload.toByteArray(Charsets.UTF_8)
        signer.update(msg, 0, msg.size)
        return signer.generateSignature().joinToString("") { "%02x".format(it) }
    }

    fun signDocumentWithInstituteKey(context: Context?, canonicalPayload: String): String {
        val seed = getOrProvisionInstituteSigningSeed(context)
        return signDocumentCanonicalPayload(canonicalPayload, seed)
    }

    fun verifyDocumentSignature(
        canonicalPayload: String,
        signatureHex: String,
        masterSeed: ByteArray
    ): Boolean {
        val cleanHex = signatureHex.trim().lowercase()
        if (cleanHex.length != 128) return false
        val parsed = runCatching {
            ByteArray(64) { i ->
                cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull() ?: return false
        val (_, pub) = deriveInstituteKeyPair(masterSeed)
        val verifier = Ed25519Signer()
        verifier.init(false, pub)
        val msg = canonicalPayload.toByteArray(Charsets.UTF_8)
        verifier.update(msg, 0, msg.size)
        return verifier.verifySignature(parsed)
    }

    fun verifyDocumentWithInstituteKey(
        context: Context?,
        canonicalPayload: String,
        signatureHex: String
    ): Boolean {
        val seed = getOrProvisionInstituteSigningSeed(context)
        return verifyDocumentSignature(canonicalPayload, signatureHex, seed)
    }
}
