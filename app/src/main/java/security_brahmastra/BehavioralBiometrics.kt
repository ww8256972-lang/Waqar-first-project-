package security_brahmastra

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/**
 * 3. `security_brahmastra/BehavioralBiometrics.kt`
 * Keystroke Dwell-Time, Flight-Time & Touch/Pointer Entropy Profiler.
 *
 * Builds a behavioral baseline during the initial window and detects >35% rhythm deviation
 * without interrupting the exam UI.
 */
class BehavioralBiometrics(
    private val deviationThresholdRatio: Double = 0.35
) {
    data class BehavioralVector(
        val meanDwellMs: Double,
        val meanFlightMs: Double,
        val pointerEntropy: Double
    )

    data class BehavioralAnomalyEvent(
        val deviationRatio: Double,
        val baseline: BehavioralVector,
        val current: BehavioralVector,
        val timestampEpochMs: Long,
        val reason: String
    )

    private val dwellSamplesMs = CopyOnWriteArrayList<Long>()
    private val flightSamplesMs = CopyOnWriteArrayList<Long>()
    private val pointerStepDistances = CopyOnWriteArrayList<Double>()
    private val anomalyEvents = CopyOnWriteArrayList<BehavioralAnomalyEvent>()

    @Volatile
    private var lastKeyDownMs: Long = 0L
    @Volatile
    private var lastKeyUpMs: Long = 0L
    @Volatile
    private var lastPointerX: Float = Float.NaN
    @Volatile
    private var lastPointerY: Float = Float.NaN
    @Volatile
    private var baselineVector: BehavioralVector? = null

    fun recordKeyDown(timestampMs: Long = System.currentTimeMillis()) {
        if (lastKeyUpMs > 0L && timestampMs >= lastKeyUpMs) {
            flightSamplesMs.add((timestampMs - lastKeyUpMs).coerceAtMost(5_000L))
        }
        lastKeyDownMs = timestampMs
    }

    fun recordKeyUp(timestampMs: Long = System.currentTimeMillis()) {
        if (lastKeyDownMs > 0L && timestampMs >= lastKeyDownMs) {
            dwellSamplesMs.add((timestampMs - lastKeyDownMs).coerceAtMost(2_000L))
        }
        lastKeyUpMs = timestampMs
    }

    fun recordPointerMove(x: Float, y: Float) {
        if (!lastPointerX.isNaN() && !lastPointerY.isNaN()) {
            val dist = hypot((x - lastPointerX).toDouble(), (y - lastPointerY).toDouble())
            if (dist > 0.0) {
                pointerStepDistances.add(dist)
            }
        }
        lastPointerX = x
        lastPointerY = y
    }

    fun lockBaselineFromCurrentSamples(): BehavioralVector {
        val vector = computeCurrentVector()
        baselineVector = vector
        return vector
    }

    fun computeCurrentVector(): BehavioralVector {
        val meanDwell = if (dwellSamplesMs.isEmpty()) 95.0 else dwellSamplesMs.average()
        val meanFlight = if (flightSamplesMs.isEmpty()) 140.0 else flightSamplesMs.average()
        val entropy = computeShannonEntropy(pointerStepDistances)
        return BehavioralVector(
            meanDwellMs = meanDwell,
            meanFlightMs = meanFlight,
            pointerEntropy = entropy
        )
    }

    fun evaluateRhythmDeviation(
        candidateVector: BehavioralVector = computeCurrentVector()
    ): BehavioralAnomalyEvent? {
        val base = baselineVector ?: lockBaselineFromCurrentSamples()
        val dwellDiff = abs(candidateVector.meanDwellMs - base.meanDwellMs) / base.meanDwellMs.coerceAtLeast(1.0)
        val flightDiff = abs(candidateVector.meanFlightMs - base.meanFlightMs) / base.meanFlightMs.coerceAtLeast(1.0)
        val combinedRatio = (dwellDiff * 0.5) + (flightDiff * 0.5)

        if (combinedRatio > deviationThresholdRatio) {
            val event = BehavioralAnomalyEvent(
                deviationRatio = combinedRatio,
                baseline = base,
                current = candidateVector,
                timestampEpochMs = System.currentTimeMillis(),
                reason = "TYPING_RHYTHM_DEVIATION_${(combinedRatio * 100).toInt()}PCT"
            )
            anomalyEvents.add(event)
            return event
        }
        return null
    }

    fun getAnomalyEvents(): List<BehavioralAnomalyEvent> = anomalyEvents.toList()

    private fun computeShannonEntropy(distances: List<Double>): Double {
        if (distances.isEmpty()) return 1.0
        val buckets = IntArray(8)
        for (d in distances) {
            val idx = (d / 25.0).toInt().coerceIn(0, 7)
            buckets[idx]++
        }
        val total = distances.size.toDouble()
        var entropy = 0.0
        for (count in buckets) {
            if (count > 0) {
                val p = count / total
                entropy -= p * (ln(p) / ln(2.0))
            }
        }
        return entropy
    }
}
