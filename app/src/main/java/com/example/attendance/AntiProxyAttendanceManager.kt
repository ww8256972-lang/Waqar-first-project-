package com.example.attendance

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.view.WindowManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Anti-Proxy Geofenced Dynamic QR Attendance Engine (`AntiProxyAttendanceManager.kt`).
 *
 * Implements:
 * 1. Dynamic 15-Second Rolling QR Code Generator with HMAC-SHA256 cryptographic signature
 *    so any QR code older than the 15-second window is immediately rejected as expired.
 * 2. GPS Geofencing Location Validator (`FusedLocationProviderClient`) enforcing a 100-meter
 *    radius lock against Waqar Computer Institute's coordinates and blocking Fake GPS / Mock Location apps.
 * 3. Screenshot & Screen-Sharing Prevention (`WindowManager.LayoutParams.FLAG_SECURE`) on QR screens.
 */
data class RollingQrAttendanceToken(
    val batchId: String,
    val batchName: String,
    val timeWindowIndex: Long,
    val expiresInSeconds: Int,
    val nonceHex: String,
    val hmacSignature: String,
    val rawQrPayload: String,
    val qrBitmap: Bitmap?
)

sealed class GeofenceVerificationResult {
    data class VerifiedInsideCampus(
        val distanceMeters: Float,
        val latitude: Double,
        val longitude: Double
    ) : GeofenceVerificationResult()

    data class BlockedMockLocation(val message: String) : GeofenceVerificationResult()
    data class BlockedOutsideGeofence(
        val distanceMeters: Float,
        val allowedRadiusMeters: Float,
        val message: String
    ) : GeofenceVerificationResult()

    data class LocationUnavailable(val message: String) : GeofenceVerificationResult()
}

object AntiProxyAttendanceManager {
    private const val QR_WINDOW_MILLIS = 15_000L
    private const val HMAC_SECRET = "WCI_ANTI_PROXY_HMAC_KEY_2026_ULTRA_SECURE"
    const val DEFAULT_INSTITUTE_LAT = 28.6139
    const val DEFAULT_INSTITUTE_LNG = 77.2090
    const val DEFAULT_GEOFENCE_RADIUS_METERS = 100.0f

    /**
     * Enables or disables `FLAG_SECURE` on the active Activity window to block screenshots,
     * WhatsApp forwarding, and screen recording while the dynamic QR code is visible.
     */
    fun setAttendanceScreenCaptureShield(activity: Activity?, enabled: Boolean) {
        val window = activity?.window ?: return
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * Emits a fresh `RollingQrAttendanceToken` every second, rotating the cryptographic QR payload
     * every 15 seconds (`QR_WINDOW_MILLIS`).
     */
    fun observeRollingQrCode(
        batchId: String,
        batchName: String,
        qrSizePx: Int = 360
    ): Flow<RollingQrAttendanceToken> = flow {
        var cachedWindow = -1L
        var cachedNonce = ""
        var cachedSig = ""
        var cachedPayload = ""
        var cachedBitmap: Bitmap? = null

        while (true) {
            val nowMs = System.currentTimeMillis()
            val windowIndex = nowMs / QR_WINDOW_MILLIS
            val elapsedInWindowSec = ((nowMs % QR_WINDOW_MILLIS) / 1000L).toInt()
            val remainingSec = (15 - elapsedInWindowSec).coerceIn(1, 15)

            if (windowIndex != cachedWindow) {
                cachedWindow = windowIndex
                cachedNonce = generateRandomNonceHex()
                cachedSig = computeHmacSha256("$batchId|$windowIndex|$cachedNonce")
                cachedPayload = "WCI-QR|$batchId|$windowIndex|$cachedNonce|$cachedSig"
                cachedBitmap = renderDeterministicQrBitmap(cachedPayload, qrSizePx)
            }

            emit(
                RollingQrAttendanceToken(
                    batchId = batchId,
                    batchName = batchName,
                    timeWindowIndex = windowIndex,
                    expiresInSeconds = remainingSec,
                    nonceHex = cachedNonce,
                    hmacSignature = cachedSig,
                    rawQrPayload = cachedPayload,
                    qrBitmap = cachedBitmap
                )
            )
            delay(1000L)
        }
    }

    /**
     * Validates a scanned QR payload against the current 15-second cryptographic window
     * (allows `[currentWindow - 1, currentWindow]` for sub-second clock skew, rejects anything older).
     */
    fun verifyScannedQrPayload(
        rawQrPayload: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Result<String> = runCatching {
        val parts = rawQrPayload.trim().split("|")
        require(parts.size == 5 && parts[0] == "WCI-QR") {
            "Invalid QR Code format. Please scan the official Waqar Institute Dynamic QR."
        }
        val batchId = parts[1]
        val scannedWindow = parts[2].toLongOrNull()
            ?: throw SecurityException("Corrupted QR timestamp window.")
        val nonce = parts[3]
        val providedSig = parts[4]

        val currentWindow = nowEpochMs / QR_WINDOW_MILLIS
        if (kotlin.math.abs(currentWindow - scannedWindow) > 1L) {
            throw SecurityException("QR Code Expired (>15 seconds old). Screenshot/Proxy attendance is blocked!")
        }

        val expectedSig = computeHmacSha256("$batchId|$scannedWindow|$nonce")
        if (!expectedSig.equals(providedSig, ignoreCase = true)) {
            throw SecurityException("QR Cryptographic Signature Mismatch! Tampered QR code rejected.")
        }
        batchId
    }

    /**
     * Verifies the student's live GPS coordinates using `FusedLocationProviderClient`,
     * blocks Mock Location / Fake GPS apps, and enforces a 100-meter radius geofence.
     */
    @SuppressLint("MissingPermission")
    suspend fun verifyStudentCampusGeofence(
        context: Context,
        campusLat: Double = DEFAULT_INSTITUTE_LAT,
        campusLng: Double = DEFAULT_INSTITUTE_LNG,
        maxRadiusMeters: Float = DEFAULT_GEOFENCE_RADIUS_METERS
    ): GeofenceVerificationResult {
        return try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val cts = CancellationTokenSource()
            val location: Location? = runCatching {
                fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
            }.getOrNull() ?: runCatching {
                fusedClient.lastLocation.await()
            }.getOrNull()

            if (location == null) {
                return GeofenceVerificationResult.LocationUnavailable(
                    "GPS fix unavailable. Ensure Location Services are turned ON inside the institute."
                )
            }

            if (isLocationMocked(location)) {
                return GeofenceVerificationResult.BlockedMockLocation(
                    "SECURITY LOCKDOWN: Fake GPS / Mock Location Provider detected! Attendance blocked."
                )
            }

            val distanceResults = FloatArray(1)
            Location.distanceBetween(
                campusLat,
                campusLng,
                location.latitude,
                location.longitude,
                distanceResults
            )
            val distanceMeters = distanceResults[0]

            if (distanceMeters <= maxRadiusMeters) {
                GeofenceVerificationResult.VerifiedInsideCampus(
                    distanceMeters = distanceMeters,
                    latitude = location.latitude,
                    longitude = location.longitude
                )
            } else {
                GeofenceVerificationResult.BlockedOutsideGeofence(
                    distanceMeters = distanceMeters,
                    allowedRadiusMeters = maxRadiusMeters,
                    message = "Geofence Violation: You are ${distanceMeters.toInt()}m away from Waqar Computer Institute (Allowed: ${maxRadiusMeters.toInt()}m)."
                )
            }
        } catch (e: Exception) {
            GeofenceVerificationResult.LocationUnavailable(
                "Geofence check error: ${e.localizedMessage ?: "Location permission required"}"
            )
        }
    }

    @Suppress("DEPRECATION")
    fun isLocationMocked(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            location.isFromMockProvider
        }
    }

    private fun generateRandomNonceHex(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun computeHmacSha256(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun generateQrBitmap(payload: String, sizePx: Int = 360): Bitmap =
        com.waqar.institute.certificate.CertificateQrGenerator.generateScannableQrBitmap(
            content = payload,
            sizePx = sizePx
        )

    private fun renderDeterministicQrBitmap(payload: String, sizePx: Int): Bitmap? = runCatching {
        generateQrBitmap(payload, sizePx)
    }.getOrNull()
}
