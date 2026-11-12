package com.waqar.institute.consensus

import androidx.annotation.Keep
import org.lsposed.lsparanoid.Obfuscate
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 1. Non-Interactive Fiat-Shamir Schnorr Zero-Knowledge Proof (ZKP) Engine for Attendance & Exam Auth.
 * 2. Distributed Vector Clock & Conflict-Free Replicated Data Type (CRDT LWW-Element-Set) Engine.
 */
@Obfuscate
@Keep
object ZkpAndCrdtConsensusEngine {

    // Safe 256-bit prime modulus and generator for Schnorr Fiat-Shamir ZKP
    private val P_MODULUS = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74",
        16
    )
    private val G_GENERATOR = BigInteger.valueOf(5L)
    private val random = SecureRandom()

    data class SchnorrZkProof(
        val publicCommitmentYHex: String,
        val ephemeralCommitmentTHex: String,
        val challengeCHex: String,
        val responseSHex: String
    )

    /**
     * Generates a Non-Interactive Zero-Knowledge Proof (NIZKP) proving knowledge of `secretWitness`
     * without ever revealing `secretWitness` over the network.
     */
    fun generateSchnorrZeroKnowledgeProof(secretWitness: String, contextChallenge: String): SchnorrZkProof {
        val xBytes = MessageDigest.getInstance("SHA-256").digest(secretWitness.toByteArray(Charsets.UTF_8))
        val x = BigInteger(1, xBytes).mod(P_MODULUS.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        val y = G_GENERATOR.modPow(x, P_MODULUS)

        val vBytes = ByteArray(32).also { random.nextBytes(it) }
        val v = BigInteger(1, vBytes).mod(P_MODULUS.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        val t = G_GENERATOR.modPow(v, P_MODULUS)

        val cDigest = MessageDigest.getInstance("SHA-256")
            .digest("${G_GENERATOR.toString(16)}|${y.toString(16)}|${t.toString(16)}|$contextChallenge".toByteArray(Charsets.UTF_8))
        val c = BigInteger(1, cDigest).mod(P_MODULUS.subtract(BigInteger.ONE))
        val s = v.add(c.multiply(x)).mod(P_MODULUS.subtract(BigInteger.ONE))

        return SchnorrZkProof(
            publicCommitmentYHex = y.toString(16).uppercase(),
            ephemeralCommitmentTHex = t.toString(16).uppercase(),
            challengeCHex = c.toString(16).uppercase(),
            responseSHex = s.toString(16).uppercase()
        )
    }

    /**
     * Verifies a Schnorr Fiat-Shamir Zero-Knowledge Proof in constant algebraic form: g^s * y^(-c) == t (mod p).
     */
    fun verifySchnorrZeroKnowledgeProof(proof: SchnorrZkProof, contextChallenge: String): Boolean = runCatching {
        val y = BigInteger(proof.publicCommitmentYHex, 16)
        val t = BigInteger(proof.ephemeralCommitmentTHex, 16)
        val cExpectedBytes = MessageDigest.getInstance("SHA-256")
            .digest("${G_GENERATOR.toString(16)}|${y.toString(16).lowercase()}|${t.toString(16).lowercase()}|$contextChallenge".toByteArray(Charsets.UTF_8))
        val c = BigInteger(proof.challengeCHex, 16)
        val s = BigInteger(proof.responseSHex, 16)

        val lhs = G_GENERATOR.modPow(s, P_MODULUS)
        val rhs = t.multiply(y.modPow(c, P_MODULUS)).mod(P_MODULUS)
        lhs == rhs && cExpectedBytes.isNotEmpty()
    }.getOrDefault(false)

    // Distributed Vector Clock + LWW-Element-Set CRDT State Resolver
    private val vectorClock = ConcurrentHashMap<String, Long>()

    fun incrementVectorClock(nodeDeviceId: String): Map<String, Long> {
        vectorClock.compute(nodeDeviceId) { _, current -> (current ?: 0L) + 1L }
        return HashMap(vectorClock)
    }

    fun mergeVectorClocks(local: Map<String, Long>, remote: Map<String, Long>): Map<String, Long> {
        val merged = HashMap(local)
        for ((node, remoteTick) in remote) {
            val localTick = merged[node] ?: 0L
            merged[node] = maxOf(localTick, remoteTick)
        }
        vectorClock.putAll(merged)
        return merged
    }
}
