package math_brahmastra_native

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 4. `math_brahmastra_native/ZkpSchnorrAuth.kt`
 * Non-Interactive Fiat-Shamir Schnorr Zero-Knowledge Proof Verifier over a Safe Prime Group.
 *
 * Proves possession of secret scalar `x` such that `Y = g^x mod p` without ever transmitting `x`.
 */
object ZkpSchnorrAuth {

    // RFC 3526 1536-bit MODP Safe Prime Group
    private val P_HEX = (
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
        "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
        "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
        "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
        "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
        "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
        "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
        "670C354E4ABC9804F1746C08CA237327FFFFFFFFFFFFFFFF"
    )
    val PRIME_P: BigInteger = BigInteger(P_HEX, 16)
    val SUBGROUP_Q: BigInteger = PRIME_P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(2L))
    val GENERATOR_G: BigInteger = BigInteger.valueOf(2L)

    data class SchnorrZeroKnowledgeProof(
        val publicKeyYHex: String,
        val commitmentTHex: String,
        val challengeCHex: String,
        val responseSHex: String,
        val challengeNonce: String
    )

    fun deriveSecretScalar(seedSecret: String): BigInteger {
        val hash = MessageDigest.getInstance("SHA-256").digest(seedSecret.toByteArray(Charsets.UTF_8))
        return BigInteger(1, hash).mod(SUBGROUP_Q.subtract(BigInteger.ONE)).add(BigInteger.ONE)
    }

    /**
     * Generates a non-interactive Schnorr Zero-Knowledge Proof:
     * - Public key: `Y = g^x mod p`
     * - Random nonce scalar `r`, commitment `T = g^r mod p`
     * - Fiat-Shamir challenge `c = H(g || Y || T || challengeNonce) mod q`
     * - Response `s = (r + c * x) mod q`
     */
    fun generateProof(
        secretScalar: BigInteger,
        challengeNonce: String
    ): SchnorrZeroKnowledgeProof {
        val x = secretScalar.mod(SUBGROUP_Q)
        val publicKeyY = GENERATOR_G.modPow(x, PRIME_P)
        val randomR = BigInteger(256, SecureRandom()).mod(SUBGROUP_Q.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        val commitmentT = GENERATOR_G.modPow(randomR, PRIME_P)
        val challengeC = computeFiatShamirChallenge(publicKeyY, commitmentT, challengeNonce)
        val responseS = randomR.add(challengeC.multiply(x)).mod(SUBGROUP_Q)

        return SchnorrZeroKnowledgeProof(
            publicKeyYHex = publicKeyY.toString(16),
            commitmentTHex = commitmentT.toString(16),
            challengeCHex = challengeC.toString(16),
            responseSHex = responseS.toString(16),
            challengeNonce = challengeNonce
        )
    }

    /**
     * Verifies `g^s * Y^(-c) == T (mod p)` and Fiat-Shamir challenge consistency.
     */
    fun verifyProof(
        publicKeyHex: String,
        commitmentHex: String,
        responseHex: String,
        challengeNonce: String
    ): Boolean {
        return runCatching {
            val y = BigInteger(publicKeyHex, 16)
            val t = BigInteger(commitmentHex, 16)
            val s = BigInteger(responseHex, 16)
            val c = computeFiatShamirChallenge(y, t, challengeNonce)

            // Check g^s mod p == (T * Y^c) mod p
            val lhs = GENERATOR_G.modPow(s, PRIME_P)
            val rhs = t.multiply(y.modPow(c, PRIME_P)).mod(PRIME_P)
            lhs == rhs
        }.getOrDefault(false)
    }

    private fun computeFiatShamirChallenge(
        publicKeyY: BigInteger,
        commitmentT: BigInteger,
        challengeNonce: String
    ): BigInteger {
        val md = MessageDigest.getInstance("SHA-256")
        val input = "${GENERATOR_G.toString(16)}|${publicKeyY.toString(16)}|${commitmentT.toString(16)}|$challengeNonce"
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest).mod(SUBGROUP_Q)
    }
}
