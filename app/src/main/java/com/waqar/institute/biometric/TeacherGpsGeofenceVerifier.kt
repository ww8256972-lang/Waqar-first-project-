package com.waqar.institute.biometric

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Teacher Portal Pre-Biometric GPS Geofencing & Anti-Spoofing Verifier
 * (`TeacherGpsGeofenceVerifier.kt`).
 *
 * Integration Rules:
 * 1. Institute Coordinates: Latitude = 26.538162, Longitude = 80.484187 (GFQM+7M7, Unnao, UP).
 * 2. Pre-Biometric Location Check: Fetches real-time GPS location via `FusedLocationProviderClient`
 *    (`Priority.PRIORITY_HIGH_ACCURACY`) before launching the Face Biometric scanner.
 * 3. Radius Validation: Uses `Location.distanceBetween()` to verify if the teacher is within a
 *    50-meter radius (`<= 50.0f`) of the institute coordinates.
 * 4. Trigger Biometric: Only if `distance <= 50.0f` and not mocked, allows the Face Biometric
 *    scanner to launch.
 * 5. Out-of-Range Block: If `distance > 50.0f`, blocks the Face Biometric scanner and surfaces the
 *    exact alert dialog message: "Attendance Blocked: You must be inside the institute premises."
 * 6. Anti-Spoofing: Rejects the location if `location.isMock` (API 31+) or `location.isFromMockProvider`
 *    is `true` to prevent Fake GPS apps.
 */
object TeacherGpsGeofenceVerifier {

    const val INSTITUTE_LATITUDE: Double = 26.538162
    const val INSTITUTE_LONGITUDE: Double = 80.484187
    const val INSTITUTE_PLUS_CODE_LABEL: String = "GFQM+7M7, Unnao, UP"
    const val MAX_RADIUS_METERS: Float = 50.0f

    const val OUT_OF_RANGE_ALERT_MESSAGE: String =
        "Attendance Blocked: You must be inside the institute premises."

    const val MOCK_LOCATION_ALERT_MESSAGE: String =
        "Attendance Blocked: Fake GPS / Mock Location detected. Please disable mock location apps."

    sealed class GeofenceVerificationResult {
        data class InsidePremises(
            val distanceMeters: Float,
            val latitude: Double,
            val longitude: Double
        ) : GeofenceVerificationResult()

        data class OutsidePremises(
            val distanceMeters: Float,
            val latitude: Double,
            val longitude: Double,
            val alertMessage: String = OUT_OF_RANGE_ALERT_MESSAGE
        ) : GeofenceVerificationResult()

        data class MockLocationRejected(
            val latitude: Double,
            val longitude: Double,
            val alertMessage: String = MOCK_LOCATION_ALERT_MESSAGE
        ) : GeofenceVerificationResult()

        data class LocationUnavailable(
            val reason: String,
            val alertMessage: String = OUT_OF_RANGE_ALERT_MESSAGE
        ) : GeofenceVerificationResult()
    }

    /**
     * Checks whether the supplied Android `Location` comes from a mock/fake GPS provider.
     */
    @Suppress("DEPRECATION")
    fun isMockLocation(location: Location): Boolean {
        val mockApi31 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            false
        }
        val mockLegacy = location.isFromMockProvider
        return mockApi31 || mockLegacy
    }

    /**
     * Computes the exact distance in meters between the teacher's coordinates and the fixed
     * Waqar Computer Institute coordinates (`26.538162, 80.484187`) using `Location.distanceBetween()`.
     */
    fun calculateDistanceFromInstituteMeters(
        teacherLatitude: Double,
        teacherLongitude: Double
    ): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            teacherLatitude,
            teacherLongitude,
            INSTITUTE_LATITUDE,
            INSTITUTE_LONGITUDE,
            results
        )
        return results[0]
    }

    /**
     * Evaluates an obtained `Location` object against anti-spoofing (`isMock` / `isFromMockProvider`)
     * and the 50-meter radius rule (`Location.distanceBetween()`).
     */
    fun evaluateLocation(location: Location?): GeofenceVerificationResult {
        if (location == null) {
            return GeofenceVerificationResult.LocationUnavailable(
                reason = "GPS fix unavailable. Ensure High-Accuracy Location is enabled."
            )
        }

        // Anti-Spoofing check: Reject Fake GPS / Mock Location providers immediately
        if (isMockLocation(location)) {
            return GeofenceVerificationResult.MockLocationRejected(
                latitude = location.latitude,
                longitude = location.longitude
            )
        }

        val distanceMeters = calculateDistanceFromInstituteMeters(
            teacherLatitude = location.latitude,
            teacherLongitude = location.longitude
        )

        return if (distanceMeters <= MAX_RADIUS_METERS) {
            GeofenceVerificationResult.InsidePremises(
                distanceMeters = distanceMeters,
                latitude = location.latitude,
                longitude = location.longitude
            )
        } else {
            GeofenceVerificationResult.OutsidePremises(
                distanceMeters = distanceMeters,
                latitude = location.latitude,
                longitude = location.longitude,
                alertMessage = OUT_OF_RANGE_ALERT_MESSAGE
            )
        }
    }

    fun hasFineOrCoarseLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Fetches the teacher's real-time GPS location using `FusedLocationProviderClient` with
     * `Priority.PRIORITY_HIGH_ACCURACY` and validates the 50-meter radius + anti-spoofing rules.
     */
    @SuppressLint("MissingPermission")
    suspend fun verifyTeacherLocationBeforeBiometric(
        context: Context,
        timeoutMs: Long = 10_000L
    ): GeofenceVerificationResult = withContext(Dispatchers.IO) {
        if (!hasFineOrCoarseLocationPermission(context)) {
            return@withContext GeofenceVerificationResult.LocationUnavailable(
                reason = "Location permission is required to verify institute premises (50m geofence)."
            )
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val cancellationSource = CancellationTokenSource()

        val currentFix: Location? = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                fusedClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationSource.token
                ).addOnSuccessListener { loc ->
                    if (cont.isActive) cont.resume(loc)
                }.addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }.addOnCanceledListener {
                    if (cont.isActive) cont.resume(null)
                }

                cont.invokeOnCancellation {
                    cancellationSource.cancel()
                }
            }
        }

        // If getCurrentLocation timed out indoors, check fresh lastLocation only if within 60 seconds
        val resolvedLocation = currentFix ?: withTimeoutOrNull(2_500L) {
            suspendCancellableCoroutine { cont ->
                fusedClient.lastLocation
                    .addOnSuccessListener { loc ->
                        val isFresh = loc != null &&
                            kotlin.math.abs(System.currentTimeMillis() - loc.time) <= 60_000L
                        if (cont.isActive) cont.resume(if (isFresh) loc else null)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
            }
        }

        evaluateLocation(resolvedLocation)
    }
}
