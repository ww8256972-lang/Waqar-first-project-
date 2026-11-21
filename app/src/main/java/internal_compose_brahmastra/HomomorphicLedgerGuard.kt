package internal_compose_brahmastra

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * 1. `internal_compose_brahmastra/HomomorphicLedgerGuard.kt`
 * Additive & Affine Homomorphic Masking (`E(x) = (a * x + b) mod p`) for In-Memory UI States
 * (Fees Balances, Exam Scores, Attendance Counts) reinforced with Android Keystore `HmacSHA256`
 * integrity sealing so masked ledger batches cannot be forged or patched in RAM.
 */
class HomomorphicLedgerGuard(
    seedBytes: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
) {
    // 127-bit Mersenne Prime p = 2^127 - 1
    private val twoVal: BigInteger = BigInteger.valueOf(2L)
    private val primeP: BigInteger = twoVal.pow(127).subtract(BigInteger.ONE)
    private val multiplierA: BigInteger
    private val invA: BigInteger
    private val localHmacFallbackKey: SecretKey

    init {
        val rawA = BigInteger(1, seedBytes).mod(primeP.subtract(twoVal)).add(twoVal)
        multiplierA = rawA
        invA = multiplierA.modInverse(primeP)
        val keyDigest = MessageDigest.getInstance("SHA-256").digest(seedBytes)
        localHmacFallbackKey = SecretKeySpec(keyDigest, "HmacSHA256")
    }

    data class MaskedValue(
        val cipherResidueHex: String,
        val blindingTagHex: String
    )

    /**
     * Masks an integer `x`:
     *   `E(x) = (a * x + b) mod p`
     */
    fun maskInteger(plainValue: Long, blindingNonce: Long = 0L): MaskedValue {
        val x = BigInteger.valueOf(plainValue).mod(primeP)
        val b = BigInteger.valueOf(blindingNonce).multiply(multiplierA).mod(primeP)
        val c = multiplierA.multiply(x).add(b).mod(primeP)
        return MaskedValue(
            cipherResidueHex = c.toString(16),
            blindingTagHex = b.toString(16)
        )
    }

    /**
     * Unmasks a [MaskedValue] back to `Long`:
     *   `x = a^-1 * (E(x) - b) mod p`
     */
    fun unmaskInteger(masked: MaskedValue): Long {
        val c = BigInteger(masked.cipherResidueHex, 16)
        val b = BigInteger(masked.blindingTagHex, 16)
        val unmasked = c.subtract(b).mod(primeP).multiply(invA).mod(primeP)
        val halfP = primeP.shiftRight(1)
        val signed = if (unmasked > halfP) unmasked.subtract(primeP) else unmasked
        return signed.toLong()
    }

    /**
     * Homomorphically adds two masked integers `E(x1)` and `E(x2)` in encrypted space without
     * decrypting either operand in RAM:
     *   `E(x1 + x2) = (E(x1) + E(x2)) mod p`
     */
    fun addHomomorphically(m1: MaskedValue, m2: MaskedValue): MaskedValue {
        val c1 = BigInteger(m1.cipherResidueHex, 16)
        val c2 = BigInteger(m2.cipherResidueHex, 16)
        val b1 = BigInteger(m1.blindingTagHex, 16)
        val b2 = BigInteger(m2.blindingTagHex, 16)
        return MaskedValue(
            cipherResidueHex = c1.add(c2).mod(primeP).toString(16),
            blindingTagHex = b1.add(b2).mod(primeP).toString(16)
        )
    }

    /**
     * Seals a batch of masked financial/academic values using an Android Keystore `HmacSHA256` key
     * so tampered RAM figures fail integrity verification before commit.
     */
    fun sealMaskedBatchWithHmac(values: List<MaskedValue>, contextTag: String): String {
        val canonical = buildString {
            append("LEDGER_SEAL_V1|").append(contextTag)
            values.forEach { v ->
                append('|').append(v.cipherResidueHex).append(':').append(v.blindingTagHex)
            }
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(resolveKeystoreHmacKey())
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun verifyMaskedBatchHmac(
        values: List<MaskedValue>,
        contextTag: String,
        expectedHmacHex: String
    ): Boolean {
        val computed = sealMaskedBatchWithHmac(values, contextTag)
        return MessageDigest.isEqual(
            computed.toByteArray(Charsets.UTF_8),
            expectedHmacHex.trim().lowercase().toByteArray(Charsets.UTF_8)
        )
    }

    private fun resolveKeystoreHmacKey(): SecretKey {
        if (Build.FINGERPRINT?.equals("robolectric", ignoreCase = true) == true) {
            return localHmacFallbackKey
        }
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(LEDGER_HMAC_ALIAS)) {
                val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
                kg.init(
                    KeyGenParameterSpec.Builder(
                        LEDGER_HMAC_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
                kg.generateKey()
            }
            (ks.getKey(LEDGER_HMAC_ALIAS, null) as? SecretKey) ?: localHmacFallbackKey
        }.getOrDefault(localHmacFallbackKey)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val LEDGER_HMAC_ALIAS = "wci_homomorphic_ledger_hmac_v1"
    }
}
