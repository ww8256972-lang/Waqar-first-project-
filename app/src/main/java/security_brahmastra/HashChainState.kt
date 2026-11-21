package security_brahmastra

import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 4. `security_brahmastra/HashChainState.kt`
 * Deterministic Sequential SHA-256 State Machine for Exam Question Progression.
 *
 * Computes `currentHash = SHA256(prevHash + questionId + selectedOption + timestamp)` on every
 * answered question and verifies the entire sequential chain on submission.
 */
class HashChainState(
    genesisSeed: String = "WCI_GENESIS_HASH_CHAIN_V1"
) {
    data class ChainStep(
        val stepIndex: Int,
        val questionId: String,
        val selectedOption: Int,
        val timestampEpochMs: Long,
        val prevHashHex: String,
        val currentHashHex: String
    )

    private val initialGenesisHashHex: String = sha256Hex(genesisSeed)
    @Volatile
    private var headHashHex: String = initialGenesisHashHex
    private val steps = CopyOnWriteArrayList<ChainStep>()

    @Synchronized
    fun recordQuestionStep(
        questionId: String,
        selectedOption: Int,
        timestampEpochMs: Long = System.currentTimeMillis()
    ): ChainStep {
        val prev = headHashHex
        val next = sha256Hex("$prev|$questionId|$selectedOption|$timestampEpochMs")
        val step = ChainStep(
            stepIndex = steps.size,
            questionId = questionId,
            selectedOption = selectedOption,
            timestampEpochMs = timestampEpochMs,
            prevHashHex = prev,
            currentHashHex = next
        )
        steps.add(step)
        headHashHex = next
        return step
    }

    fun getCurrentHeadHash(): String = headHashHex

    fun currentHeadHashHex(): String = headHashHex

    fun getRecordedSteps(): List<ChainStep> = steps.toList()

    fun stepCount(): Int = steps.size

    companion object {
        fun verifyChainProof(
            genesisSeed: String = "WCI_GENESIS_HASH_CHAIN_V1",
            steps: List<ChainStep>,
            expectedFinalHashHex: String
        ): Boolean {
            var cursor = sha256Hex(genesisSeed)
            for ((idx, step) in steps.withIndex()) {
                if (step.stepIndex != idx) return false
                if (step.prevHashHex != cursor) return false
                val recomputed = sha256Hex("${step.prevHashHex}|${step.questionId}|${step.selectedOption}|${step.timestampEpochMs}")
                if (recomputed != step.currentHashHex) return false
                cursor = recomputed
            }
            return cursor == expectedFinalHashHex
        }

        private fun sha256Hex(input: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
