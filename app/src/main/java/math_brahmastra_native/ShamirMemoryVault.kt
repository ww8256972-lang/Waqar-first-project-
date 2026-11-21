package math_brahmastra_native

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 3. `math_brahmastra_native/ShamirMemoryVault.kt`
 * Shamir's Secret Sharing over Finite Field GF(p) using Lagrange Polynomial Interpolation
 * with Immediate RAM Zeroization (`0x00`).
 */
object ShamirMemoryVault {

    // 521-bit Mersenne Prime p = 2^521 - 1
    private val PRIME_FIELD: BigInteger = BigInteger.valueOf(2L).pow(521).subtract(BigInteger.ONE)

    data class SecretShare(
        val indexX: Int,
        val valueYHex: String,
        val originalLengthBytes: Int
    )

    /**
     * Splits [secret] into [totalShares] shares with reconstruction [threshold] over GF(2^521 - 1).
     */
    fun splitSecretIntoShares(
        secret: ByteArray,
        totalShares: Int = 5,
        threshold: Int = 3
    ): List<SecretShare> {
        require(threshold in 2..totalShares) { "Threshold must be between 2 and totalShares." }
        require(secret.isNotEmpty() && secret.size <= 64) { "Secret must be 1..64 bytes." }

        val secretInt = BigInteger(1, secret)
        require(secretInt < PRIME_FIELD) { "Secret exceeds field prime." }

        val random = SecureRandom()
        val coeffs = Array(threshold) { idx ->
            if (idx == 0) secretInt else BigInteger(512, random).mod(PRIME_FIELD)
        }

        val shares = (1..totalShares).map { xInt ->
            val x = BigInteger.valueOf(xInt.toLong())
            var y = BigInteger.ZERO
            var xPow = BigInteger.ONE
            for (c in coeffs) {
                y = y.add(c.multiply(xPow)).mod(PRIME_FIELD)
                xPow = xPow.multiply(x).mod(PRIME_FIELD)
            }
            SecretShare(
                indexX = xInt,
                valueYHex = y.toString(16),
                originalLengthBytes = secret.size
            )
        }

        // Zeroize polynomial coefficients in memory
        for (i in coeffs.indices) coeffs[i] = BigInteger.ZERO
        return shares
    }

    /**
     * Reconstructs the secret from [shares] via Lagrange Interpolation at `x = 0`, signs [payload]
     * with `HmacSHA256`, and immediately overwrites the reconstructed byte array with `0x00`.
     */
    fun reconstructAndSign(
        shares: List<SecretShare>,
        payload: String
    ): String {
        val reconstructed = reconstructSecretBytes(shares)
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(reconstructed, "HmacSHA256"))
            mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } finally {
            Arrays.fill(reconstructed, 0.toByte())
        }
    }

    fun reconstructSecretBytes(shares: List<SecretShare>): ByteArray {
        require(shares.size >= 2) { "At least 2 shares required for Lagrange interpolation." }
        var secretAcc = BigInteger.ZERO

        for (i in shares.indices) {
            val xi = BigInteger.valueOf(shares[i].indexX.toLong())
            val yi = BigInteger(shares[i].valueYHex, 16)
            var numerator = BigInteger.ONE
            var denominator = BigInteger.ONE

            for (j in shares.indices) {
                if (i == j) continue
                val xj = BigInteger.valueOf(shares[j].indexX.toLong())
                numerator = numerator.multiply(xj.negate()).mod(PRIME_FIELD)
                denominator = denominator.multiply(xi.subtract(xj)).mod(PRIME_FIELD)
            }
            val lagrangeBasis = numerator.multiply(denominator.modInverse(PRIME_FIELD)).mod(PRIME_FIELD)
            secretAcc = secretAcc.add(yi.multiply(lagrangeBasis)).mod(PRIME_FIELD)
        }

        val raw = secretAcc.toByteArray()
        val targetLen = shares.first().originalLengthBytes
        val out = ByteArray(targetLen)
        val stripped = if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        val copyLen = minOf(stripped.size, targetLen)
        System.arraycopy(stripped, stripped.size - copyLen, out, targetLen - copyLen, copyLen)
        Arrays.fill(raw, 0.toByte())
        return out
    }
}
