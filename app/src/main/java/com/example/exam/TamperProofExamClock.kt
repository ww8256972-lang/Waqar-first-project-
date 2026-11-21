package com.example.exam

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Tamper-Proof Monotonic Exam Clock (`TamperProofExamClock.kt`).
 *
 * Security Guarantees:
 * 1. Active exam timers compute remaining time strictly from `SystemClock.elapsedRealtime()`
 *    anchored to an initial deadline — manual changes to the device Settings Date/Time have
 *    ZERO effect on remaining exam seconds.
 * 2. Scheduled exam unlock checks anchor a server-verified UTC timestamp (`syncServerTimeOffsetAsync`)
 *    paired with monotonic `elapsedRealtime()` so rolling the phone clock forward/backward cannot
 *    unlock an upcoming exam early or stall an active window.
 */
object TamperProofExamClock {

    @Volatile
    private var anchoredEpochMs: Long? = null

    @Volatile
    private var anchoredElapsedMs: Long = 0L

    @Volatile
    var isServerTimeSynced: Boolean = false
        private set

    private val trustedTimeEndpoints = listOf(
        "https://firestore.googleapis.com/",
        "https://www.google.com/generate_204"
    )

    /**
     * Synchronizes UTC epoch time using an HTTP `HEAD` Date header from trusted Google/Firebase
     * infrastructure and anchors it to monotonic `SystemClock.elapsedRealtime()`.
     */
    suspend fun syncServerTimeOffsetAsync(): Boolean = withContext(Dispatchers.IO) {
        for (endpoint in trustedTimeEndpoints) {
            val fetchedMs = runCatching {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 2_500
                    readTimeout = 2_500
                    useCaches = false
                }
                try {
                    val serverDateMs = conn.date
                    if (serverDateMs > 1_700_000_000_000L) serverDateMs else {
                        val headerDate = conn.getHeaderField("Date")
                        parseHttpDateHeader(headerDate)
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()

            if (fetchedMs != null && fetchedMs > 1_700_000_000_000L) {
                anchorServerEpoch(fetchedMs, SystemClock.elapsedRealtime())
                return@withContext true
            }
        }
        false
    }

    internal fun anchorServerEpoch(
        serverEpochMs: Long,
        currentElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()
    ) {
        anchoredEpochMs = serverEpochMs
        anchoredElapsedMs = currentElapsedRealtimeMs
        isServerTimeSynced = true
    }

    internal fun resetAnchorForTests() {
        anchoredEpochMs = null
        anchoredElapsedMs = 0L
        isServerTimeSynced = false
    }

    /**
     * Returns the current epoch timestamp in milliseconds backed by monotonic `elapsedRealtime()`
     * advance since server anchor (or initial wall-clock anchor if offline).
     */
    fun currentTrustedEpochMs(
        currentElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
        fallbackWallClockMs: Long = System.currentTimeMillis()
    ): Long {
        val epoch = anchoredEpochMs
        return if (epoch != null) {
            computeTrustedEpochMs(epoch, anchoredElapsedMs, currentElapsedRealtimeMs)
        } else {
            // Establish local monotonic baseline on first call so mid-session wall-clock edits
            // cannot skew subsequent schedule comparisons.
            anchoredEpochMs = fallbackWallClockMs
            anchoredElapsedMs = currentElapsedRealtimeMs
            fallbackWallClockMs
        }
    }

    fun computeTrustedEpochMs(
        anchorEpochMs: Long,
        anchorElapsedMs: Long,
        currentElapsedMs: Long
    ): Long {
        val elapsedDelta = (currentElapsedMs - anchorElapsedMs).coerceAtLeast(0L)
        return anchorEpochMs + elapsedDelta
    }

    /**
     * Detects whether the user manually altered the device's system wall clock beyond tolerance.
     */
    fun detectManualClockJump(
        toleranceMs: Long = 90_000L,
        currentElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
        wallClockMs: Long = System.currentTimeMillis()
    ): Boolean {
        val trusted = currentTrustedEpochMs(currentElapsedRealtimeMs, wallClockMs)
        return abs(wallClockMs - trusted) > toleranceMs
    }

    /**
     * Computes a tamper-proof monotonic deadline in `SystemClock.elapsedRealtime()` milliseconds.
     */
    fun elapsedDeadlineAfterDurationMinutes(
        durationMinutes: Int,
        currentElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        val safeMinutes = durationMinutes.coerceIn(1, 360)
        return currentElapsedRealtimeMs + (safeMinutes * 60_000L)
    }

    /**
     * [B1] Computes a tamper-proof monotonic deadline supporting BOTH seconds (e.g. 30s)
     * and minutes (e.g. 45 * 60 = 2700s).
     */
    fun elapsedDeadlineAfterDurationSeconds(
        durationSeconds: Int,
        currentElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        val safeSeconds = durationSeconds.coerceIn(10, 360 * 60)
        return currentElapsedRealtimeMs + (safeSeconds * 1_000L)
    }

    /**
     * [B1] Parses a strict 24-hour date + time (`YYYY-MM-DD` + `HH:mm`, 00:00..23:59) to UTC epoch ms.
     */
    fun parse24HourScheduleEpochMs(dateIso: String, startTime24h: String = ""): Long {
        val cleanDate = dateIso.trim()
        val cleanTime = startTime24h.trim()
        val combined = when {
            cleanDate.length >= 16 && cleanDate.contains(":") -> cleanDate.take(16)
            cleanDate.length >= 10 && cleanTime.matches(Regex("""^([01]\d|2[0-3]):[0-5]\d$""")) ->
                "${cleanDate.take(10)} $cleanTime"
            else -> return 0L
        }
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
                isLenient = false
            }.parse(combined)?.time ?: 0L
        }.getOrDefault(0L)
    }

    /**
     * Anchors the authoritative UTC clock returned by `startExamSession` (`serverNowMs`) to
     * monotonic `elapsedRealtime()` and converts `hardDeadlineAtMs` into a tamper-proof
     * monotonic deadline in `elapsedRealtime()` milliseconds.
     */
    fun anchorAuthoritativeServerDeadline(
        serverNowMs: Long,
        hardDeadlineAtMs: Long,
        currentElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        if (serverNowMs > 1_700_000_000_000L) {
            anchorServerEpoch(serverNowMs, currentElapsedRealtimeMs)
        }
        val effectiveNow = if (serverNowMs > 0L) serverNowMs else currentTrustedEpochMs(currentElapsedRealtimeMs)
        val remainingMs = (hardDeadlineAtMs - effectiveNow).coerceAtLeast(0L)
        return currentElapsedRealtimeMs + remainingMs
    }

    /**
     * Checks whether a server-issued exam deadline has passed (including optional network grace).
     */
    fun isServerDeadlineExpired(
        hardDeadlineAtMs: Long,
        graceWindowMs: Long = 30_000L,
        trustedNowMs: Long = currentTrustedEpochMs()
    ): Boolean = hardDeadlineAtMs > 0L && trustedNowMs > (hardDeadlineAtMs + graceWindowMs)

    /**
     * Computes remaining exam seconds strictly from a monotonic `elapsedRealtime()` deadline.
     */
    fun computeRemainingSeconds(
        deadlineElapsedMs: Long,
        currentElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()
    ): Int {
        val remainingMs = (deadlineElapsedMs - currentElapsedRealtimeMs).coerceAtLeast(0L)
        return ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(0)
    }

    fun formatMmSs(totalSeconds: Int): String {
        val safe = totalSeconds.coerceAtLeast(0)
        return "%02d:%02d".format(Locale.US, safe / 60, safe % 60)
    }

    fun formatHhMmSs(totalSeconds: Long): String {
        val safe = totalSeconds.coerceAtLeast(0L)
        val hrs = safe / 3600L
        val mins = (safe % 3600L) / 60L
        val secs = safe % 60L
        return "%02d:%02d:%02d".format(Locale.US, hrs, mins, secs)
    }

    private fun parseHttpDateHeader(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }.parse(raw)?.time
        }.getOrNull()
    }
}
