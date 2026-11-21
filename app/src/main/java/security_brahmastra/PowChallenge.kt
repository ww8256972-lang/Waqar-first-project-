package security_brahmastra

import java.security.MessageDigest

/**
 * 5. `security_brahmastra/PowChallenge.kt`
 * Client-Side Proof-of-Work (Hashcash Anti-Bot) Solver & Header Verifier (`X-PoW-Proof`).
 */
object PowChallenge {

    const val POW_HEADER_NAME = "X-PoW-Proof"

    data class PowSolution(
        val nonce: String,
        val difficulty: Int,
        val counterSuffix: Long,
        val hashHex: String,
        val elapsedMs: Long
    ) {
        fun toHeaderValue(): String = "$nonce:$difficulty:$counterSuffix:$hashHex"
    }

    /**
     * Finds a `counterSuffix` such that `SHA256("$nonce:$counterSuffix")` starts with
     * `difficulty` leading hexadecimal `'0'` characters.
     */
    fun solvePoWChallenge(
        nonce: String,
        difficulty: Int = 4,
        maxIterations: Long = 2_000_000L
    ): PowSolution {
        val safeDifficulty = difficulty.coerceIn(1, 5)
        val targetPrefix = "0".repeat(safeDifficulty)
        val startMs = System.currentTimeMillis()
        val digest = MessageDigest.getInstance("SHA-256")

        var counter = 0L
        while (counter < maxIterations) {
            val candidate = "$nonce:$counter"
            val hashBytes = digest.digest(candidate.toByteArray(Charsets.UTF_8))
            if (hasLeadingZeroHexNibbles(hashBytes, safeDifficulty)) {
                val hex = hashBytes.joinToString("") { "%02x".format(it) }
                if (hex.startsWith(targetPrefix)) {
                    return PowSolution(
                        nonce = nonce,
                        difficulty = safeDifficulty,
                        counterSuffix = counter,
                        hashHex = hex,
                        elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    )
                }
            }
            counter++
        }
        val fallbackHex = digest.digest("$nonce:$counter".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return PowSolution(
            nonce = nonce,
            difficulty = safeDifficulty,
            counterSuffix = counter,
            hashHex = fallbackHex,
            elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        )
    }

    fun verifyPoWHeader(
        headerValue: String,
        expectedNonce: String? = null,
        minDifficulty: Int = 3
    ): Boolean {
        val parts = headerValue.trim().split(":")
        if (parts.size != 4) return false
        val nonce = parts[0]
        val difficulty = parts[1].toIntOrNull() ?: return false
        val suffix = parts[2].toLongOrNull() ?: return false
        val claimedHash = parts[3]

        if (expectedNonce != null && nonce != expectedNonce) return false
        if (difficulty < minDifficulty) return false
        val prefix = "0".repeat(difficulty)
        if (!claimedHash.startsWith(prefix)) return false

        val actualHash = MessageDigest.getInstance("SHA-256")
            .digest("$nonce:$suffix".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return actualHash.equals(claimedHash, ignoreCase = true)
    }

    private fun hasLeadingZeroHexNibbles(bytes: ByteArray, nibbles: Int): Boolean {
        val fullZeroBytes = nibbles / 2
        for (i in 0 until fullZeroBytes) {
            if (bytes[i].toInt() != 0) return false
        }
        if (nibbles % 2 == 1) {
            val highNibble = (bytes[fullZeroBytes].toInt() ushr 4) and 0x0F
            if (highNibble != 0) return false
        }
        return true
    }
}
