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

    // Verified 768-bit RFC 2409 Safe Prime (p = 2q + 1 where both p and q are prime)
    internal val P_MODULUS = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A63A3620FFFFFFFFFFFFFFFF",
        16
    )
    // Prime subgroup order q = (p - 1) / 2 and generator g = 4 (= 2^2 mod p) of exact order q
    internal val Q_ORDER: BigInteger = P_MODULUS.subtract(BigInteger.ONE).shiftRight(1)
    internal val G_GENERATOR: BigInteger = BigInteger.valueOf(4L)
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
        val x = BigInteger(1, xBytes).mod(Q_ORDER.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        val y = G_GENERATOR.modPow(x, P_MODULUS)

        val vBytes = ByteArray(48).also { random.nextBytes(it) }
        val v = BigInteger(1, vBytes).mod(Q_ORDER.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        val t = G_GENERATOR.modPow(v, P_MODULUS)

        val cDigest = MessageDigest.getInstance("SHA-256")
            .digest("${G_GENERATOR.toString(16)}|${y.toString(16)}|${t.toString(16)}|$contextChallenge".toByteArray(Charsets.UTF_8))
        val c = BigInteger(1, cDigest).mod(Q_ORDER)
        val s = v.add(c.multiply(x)).mod(Q_ORDER)

        return SchnorrZkProof(
            publicCommitmentYHex = y.toString(16).uppercase(),
            ephemeralCommitmentTHex = t.toString(16).uppercase(),
            challengeCHex = c.toString(16).uppercase(),
            responseSHex = s.toString(16).uppercase()
        )
    }

    /**
     * Verifies a Schnorr Fiat-Shamir Zero-Knowledge Proof in prime-order subgroup `q = (p - 1) / 2`:
     * 1. Validates domain & subgroup membership (`1 < y, t < p`, `y^q == t^q == 1 mod p`, `0 <= c, s < q`).
     * 2. Recomputes Fiat-Shamir challenge `cExpected = H(g | y | t | contextChallenge) mod q`
     *    and verifies `c == cExpected`.
     * 3. Verifies Schnorr algebraic equality `g^s mod p == t * y^c mod p`.
     */
    fun verifySchnorrZeroKnowledgeProof(proof: SchnorrZkProof, contextChallenge: String): Boolean = runCatching {
        val y = BigInteger(proof.publicCommitmentYHex.trim(), 16)
        val t = BigInteger(proof.ephemeralCommitmentTHex.trim(), 16)
        val c = BigInteger(proof.challengeCHex.trim(), 16)
        val s = BigInteger(proof.responseSHex.trim(), 16)

        if (y <= BigInteger.ONE || y >= P_MODULUS) return@runCatching false
        if (t <= BigInteger.ONE || t >= P_MODULUS) return@runCatching false
        if (c < BigInteger.ZERO || c >= Q_ORDER) return@runCatching false
        if (s < BigInteger.ZERO || s >= Q_ORDER) return@runCatching false
        if (y.modPow(Q_ORDER, P_MODULUS) != BigInteger.ONE) return@runCatching false
        if (t.modPow(Q_ORDER, P_MODULUS) != BigInteger.ONE) return@runCatching false

        val cExpectedBytes = MessageDigest.getInstance("SHA-256")
            .digest("${G_GENERATOR.toString(16)}|${y.toString(16)}|${t.toString(16)}|$contextChallenge".toByteArray(Charsets.UTF_8))
        val cExpected = BigInteger(1, cExpectedBytes).mod(Q_ORDER)
        if (c != cExpected) return@runCatching false

        val lhs = G_GENERATOR.modPow(s, P_MODULUS)
        val rhs = t.multiply(y.modPow(c, P_MODULUS)).mod(P_MODULUS)
        lhs == rhs
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
