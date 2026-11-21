package quantum_physical_shield

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 5. `quantum_physical_shield/KolmogorovTouchTopology.kt`
 * Real Android `MotionEvent` Overlay-Flag Inspector + Kolmogorov-Arnold Univariate B-Spline
 * Touch & Micro-Tilt Authenticator.
 *
 * Inspects `MotionEvent.FLAG_WINDOW_IS_OBSCURED` & `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` alongside
 * real touch contact radius, pressure derivative `dP/dt`, and gyroscope micro-tilt.
 */
object KolmogorovTouchTopology {

    data class TouchKinematicSample(
        val contactRadiusPx: Float,
        val pressure: Float,
        val prevPressure: Float,
        val gyroMicroTiltRad: Float,
        val deltaTimeMs: Long = 16L,
        val motionFlags: Int = 0
    )

    data class KolmogorovVerdict(
        val splineActivationScore: Double,
        val pressureDerivative: Double,
        val isGenuineBiomechanicalTouch: Boolean,
        val overlayFlagsDetected: Boolean = false
    )

    fun evaluateMotionEvent(
        ev: MotionEvent,
        prevPressure: Float,
        gyroMicroTiltRad: Float,
        deltaTimeMs: Long = 16L
    ): KolmogorovVerdict {
        val radiusPx = hypot(ev.touchMajor.toDouble(), ev.touchMinor.toDouble())
            .toFloat()
            .coerceAtLeast(1f)
        return evaluateTouchSample(
            TouchKinematicSample(
                contactRadiusPx = radiusPx,
                pressure = ev.pressure,
                prevPressure = prevPressure,
                gyroMicroTiltRad = gyroMicroTiltRad,
                deltaTimeMs = deltaTimeMs,
                motionFlags = ev.flags
            )
        )
    }

    fun evaluateTouchSample(sample: TouchKinematicSample): KolmogorovVerdict {
        val obscured = (sample.motionFlags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0 ||
            (sample.motionFlags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
        if (obscured) {
            return KolmogorovVerdict(
                splineActivationScore = 0.0,
                pressureDerivative = 0.0,
                isGenuineBiomechanicalTouch = false,
                overlayFlagsDetected = true
            )
        }

        val dtSec = (sample.deltaTimeMs.coerceAtLeast(1L)) / 1000.0
        val dPdt = abs((sample.pressure - sample.prevPressure) / dtSec)

        // Normalize features into [0, 1] domain for univariate cubic B-spline basis evaluation
        val x1 = (sample.contactRadiusPx / 64.0).coerceIn(0.0, 1.0)
        val x2 = (dPdt / 10.0).coerceIn(0.0, 1.0)
        val x3 = (abs(sample.gyroMicroTiltRad) * 50.0).coerceIn(0.0, 1.0)

        var outerSum = 0.0
        for (q in 0..6) {
            val shift = q * 0.12
            val inner = cubicBSpline((x1 + shift) % 1.0) * 0.45 +
                cubicBSpline((x2 + shift) % 1.0) * 0.35 +
                cubicBSpline((x3 + shift) % 1.0) * 0.20
            outerSum += cubicBSpline(inner.coerceIn(0.0, 1.0))
        }

        val hasOrganicVariation = sample.contactRadiusPx > 0.5f &&
            (dPdt > 1e-4 || abs(sample.gyroMicroTiltRad) > 1e-5f) &&
            outerSum > 0.1
        return KolmogorovVerdict(
            splineActivationScore = outerSum,
            pressureDerivative = dPdt,
            isGenuineBiomechanicalTouch = hasOrganicVariation,
            overlayFlagsDetected = false
        )
    }

    private fun cubicBSpline(u: Double): Double {
        val t = (u * 2.0).coerceIn(0.0, 2.0)
        return when {
            t < 1.0 -> (1.0 / 6.0) * (4.0 - 6.0 * t * t + 3.0 * t * t * t)
            else -> {
                val s = 2.0 - t
                (1.0 / 6.0) * s * s * s
            }
        }
    }
}
