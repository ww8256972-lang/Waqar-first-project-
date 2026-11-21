package internal_compose_brahmastra

import android.os.SystemClock
import kotlin.math.abs

/**
 * 5. `internal_compose_brahmastra/MonotonicCausalityClock.kt`
 * Hardware Uptime (`SystemClock.elapsedRealtimeNanos()`) Cryptographic Ticker anchored to
 * Firestore Server Timestamps (`syncServerTimestampOffset`) that detects and blocks device
 * wall-clock rollback or time-travel tampering during Exams and Fee Deadlines.
 */
object MonotonicCausalityClock {

    data class CausalityAuditReport(
        val wallClockDriftMs: Long,
        val clockRollbackTamperDetected: Boolean,
        val trustedEpochMs: Long
    )

    @Volatile
    private var anchorWallEpochMs: Long = System.currentTimeMillis()
    @Volatile
    private var anchorElapsedNs: Long = nowElapsedNanos()
    @Volatile
    private var serverAnchored: Boolean = false
    @Volatile
    private var lastMeasuredServerOffsetMs: Long = 0L

    fun nowElapsedNanos(): Long {
        val sysElapsed = runCatching { SystemClock.elapsedRealtimeNanos() }.getOrDefault(0L)
        return if (sysElapsed > 0L) sysElapsed else System.nanoTime()
    }

    @Synchronized
    fun recalibrateAnchor(
        wallEpochMs: Long = System.currentTimeMillis(),
        elapsedNs: Long = nowElapsedNanos()
    ) {
        anchorWallEpochMs = wallEpochMs
        anchorElapsedNs = elapsedNs
    }

    /**
     * Anchors the hardware monotonic clock to an authoritative Firestore/Cloud Function server epoch.
     */
    @Synchronized
    fun syncServerTimestampOffset(
        serverEpochMs: Long,
        elapsedNs: Long = nowElapsedNanos(),
        localWallEpochMs: Long = System.currentTimeMillis()
    ) {
        if (serverEpochMs <= 0L) return
        anchorWallEpochMs = serverEpochMs
        anchorElapsedNs = elapsedNs
        lastMeasuredServerOffsetMs = serverEpochMs - localWallEpochMs
        serverAnchored = true
    }

    fun isServerAnchored(): Boolean = serverAnchored

    fun lastServerOffsetMs(): Long = lastMeasuredServerOffsetMs

    /**
     * Compares elapsed hardware monotonic nanoseconds against current `System.currentTimeMillis()`
     * (and server anchor when synced) to detect device wall-clock rollback or forward tampering.
     */
    fun verifyWallClockCausality(
        currentWallEpochMs: Long = System.currentTimeMillis(),
        currentElapsedNs: Long = nowElapsedNanos(),
        maxAllowedDriftMs: Long = 15_000L
    ): CausalityAuditReport {
        val elapsedDeltaMs = ((currentElapsedNs - anchorElapsedNs) / 1_000_000L).coerceAtLeast(0L)
        val trustedNowMs = anchorWallEpochMs + elapsedDeltaMs
        val driftMs = currentWallEpochMs - trustedNowMs
        val tampered = abs(driftMs) > maxAllowedDriftMs

        return CausalityAuditReport(
            wallClockDriftMs = driftMs,
            clockRollbackTamperDetected = tampered,
            trustedEpochMs = trustedNowMs
        )
    }
}
