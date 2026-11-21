package com.waqar.institute.ledger

import androidx.annotation.Keep
import com.google.firebase.firestore.FirebaseFirestore
import com.waqar.institute.consensus.ZkpAndCrdtConsensusEngine
import org.lsposed.lsparanoid.Obfuscate
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic Event-Sourced Immutable Cryptographic Audit Ledger & State Snapshot Machine.
 * Chains every critical ERP event via SHA-256 Merkle links (`prevEventHash -> currentEventHash`).
 */
@Obfuscate
@Keep
object ImmutableEventSourcedLedger {

    data class LedgerEventEntry(
        val sequenceIndex: Long,
        val eventType: String,
        val actorId: String,
        val payloadSummary: String,
        val prevEventHash: String,
        val currentEventHash: String,
        val vectorClock: Map<String, Long>,
        val timestampMs: Long
    )

    private val chain = CopyOnWriteArrayList<LedgerEventEntry>()

    @Volatile
    private var headHash: String =
        "0000000000000000000000000000000000000000000000000000000000000000"

    @Synchronized
    fun appendImmutableEvent(
        nodeDeviceId: String,
        eventType: String,
        actorId: String,
        payloadSummary: String
    ): LedgerEventEntry {
        val seq = (chain.size + 1).toLong()
        val now = System.currentTimeMillis()
        val vClock = ZkpAndCrdtConsensusEngine.incrementVectorClock(nodeDeviceId)
        val rawBlock = "$seq|$headHash|$eventType|$actorId|$payloadSummary|$vClock|$now"
        val currentHash = MessageDigest.getInstance("SHA-256")
            .digest(rawBlock.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

        val entry = LedgerEventEntry(
            sequenceIndex = seq,
            eventType = eventType,
            actorId = actorId,
            payloadSummary = payloadSummary,
            prevEventHash = headHash,
            currentEventHash = currentHash,
            vectorClock = vClock,
            timestampMs = now
        )

        headHash = currentHash
        chain.add(entry)

        // Sync immutable Merkle block to Cloud Firestore `/immutableLedger`
        runCatching {
            com.example.data.firestore.InstituteRepository.getConfiguredFirestore()
                .collection("immutableLedger")
                .document("EVT_${seq}_${currentHash.take(12)}")
                .set(
                    mapOf(
                        "sequenceIndex" to seq,
                        "eventType" to eventType,
                        "actorId" to actorId,
                        "payloadSummary" to payloadSummary,
                        "prevEventHash" to entry.prevEventHash,
                        "currentEventHash" to entry.currentEventHash,
                        "timestampMs" to now
                    )
                )
        }

        return entry
    }

    private const val GENESIS_HASH =
        "0000000000000000000000000000000000000000000000000000000000000000"

    @Synchronized
    fun verifyMerkleChainIntegrity(): Boolean {
        if (chain.isEmpty()) {
            return headHash == GENESIS_HASH
        }
        var expectedPrev = GENESIS_HASH
        for ((index, entry) in chain.withIndex()) {
            val expectedSeq = (index + 1).toLong()
            if (entry.sequenceIndex != expectedSeq) return false
            if (entry.prevEventHash != expectedPrev) return false

            val rawBlock = "${entry.sequenceIndex}|${entry.prevEventHash}|${entry.eventType}|${entry.actorId}|${entry.payloadSummary}|${entry.vectorClock}|${entry.timestampMs}"
            val recomputedHash = MessageDigest.getInstance("SHA-256")
                .digest(rawBlock.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02X".format(it) }

            if (!MessageDigest.isEqual(
                    recomputedHash.toByteArray(Charsets.UTF_8),
                    entry.currentEventHash.toByteArray(Charsets.UTF_8)
                )
            ) {
                return false
            }
            expectedPrev = entry.currentEventHash
        }
        return expectedPrev == headHash
    }

    /**
     * Verifies an arbitrary exported or deserialized event list against the same SHA-256 Merkle rules.
     */
    fun verifyExternalMerkleChain(entries: List<LedgerEventEntry>): Boolean {
        if (entries.isEmpty()) return true
        var expectedPrev = GENESIS_HASH
        for ((index, entry) in entries.withIndex()) {
            if (entry.sequenceIndex != (index + 1).toLong()) return false
            if (entry.prevEventHash != expectedPrev) return false
            val rawBlock = "${entry.sequenceIndex}|${entry.prevEventHash}|${entry.eventType}|${entry.actorId}|${entry.payloadSummary}|${entry.vectorClock}|${entry.timestampMs}"
            val recomputedHash = MessageDigest.getInstance("SHA-256")
                .digest(rawBlock.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02X".format(it) }
            if (!MessageDigest.isEqual(
                    recomputedHash.toByteArray(Charsets.UTF_8),
                    entry.currentEventHash.toByteArray(Charsets.UTF_8)
                )
            ) {
                return false
            }
            expectedPrev = entry.currentEventHash
        }
        return true
    }

    fun latestMerkleRootHex(): String = headHash.take(16)
}
