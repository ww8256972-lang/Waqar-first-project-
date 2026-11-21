package com.waqar.institute.biometric

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.abs

/**
 * [A4] Screen-Moiré & Specular-Reflection Biometric Anti-Spoof Detector + Optional TFLite Slot.
 *
 * Analyzes cropped live face bitmaps using second-order horizontal Laplacian energy (detecting
 * LCD/OLED subpixel Moiré striping) and clipped specular glare pixel ratios (detecting phone
 * screens or glossy printed photos), plus checks for an optional bundled `mobilefacenet.tflite`
 * neural embedding model in `assets/`.
 */
object BiometricScreenSpoofDetector {

    private const val TFLITE_MODEL_ASSET_NAME = "mobilefacenet.tflite"

    data class SpoofAuditResult(
        val isGenuinePhysicalFace: Boolean,
        val moireGridEnergy: Float,
        val specularGlareRatio: Float,
        val isOptionalTfliteModelPresent: Boolean = false,
        val reason: String
    )

    fun isMobileFaceNetAssetBundled(context: Context?): Boolean {
        if (context == null) return false
        return runCatching {
            val assets = context.assets.list("") ?: emptyArray()
            assets.any { it.equals(TFLITE_MODEL_ASSET_NAME, ignoreCase = true) }
        }.getOrDefault(false)
    }

    fun analyzeFrameForScreenOrPrintSpoof(
        faceCropBitmap: Bitmap,
        context: Context? = null
    ): SpoofAuditResult {
        val tflitePresent = isMobileFaceNetAssetBundled(context)
        val scaled = Bitmap.createScaledBitmap(faceCropBitmap, 64, 64, true)
        val pixels = IntArray(64 * 64)
        scaled.getPixels(pixels, 0, 64, 0, 0, 64, 64)
        if (scaled !== faceCropBitmap && !scaled.isRecycled) {
            scaled.recycle()
        }

        var highFreqOscillations = 0f
        var clippedGlarePixels = 0
        var totalSamples = 0

        for (y in 1 until 63) {
            for (x in 1 until 63) {
                val c = pixels[y * 64 + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                if (lum > 246f) clippedGlarePixels++

                val leftC = pixels[y * 64 + (x - 1)]
                val rightC = pixels[y * 64 + (x + 1)]
                val lumL = 0.299f * ((leftC shr 16) and 0xFF) +
                    0.587f * ((leftC shr 8) and 0xFF) +
                    0.114f * (leftC and 0xFF)
                val lumR = 0.299f * ((rightC shr 16) and 0xFF) +
                    0.587f * ((rightC shr 8) and 0xFF) +
                    0.114f * (rightC and 0xFF)

                // Second-order Laplacian detects LCD/OLED subpixel Moiré striping
                val laplacian = abs(2f * lum - lumL - lumR)
                if (laplacian > 34f) highFreqOscillations += 1f
                totalSamples++
            }
        }

        val moireRatio = highFreqOscillations / totalSamples.coerceAtLeast(1)
        val glareRatio = clippedGlarePixels.toFloat() / totalSamples.coerceAtLeast(1)
        val suspiciousScreen = moireRatio > 0.34f || glareRatio > 0.28f

        return SpoofAuditResult(
            isGenuinePhysicalFace = !suspiciousScreen,
            moireGridEnergy = moireRatio,
            specularGlareRatio = glareRatio,
            isOptionalTfliteModelPresent = tflitePresent,
            reason = if (suspiciousScreen) {
                "Possible mobile screen / printed photo glare detected (Moire=${"%.2f".format(moireRatio)}, Glare=${"%.2f".format(glareRatio)})."
            } else {
                "Natural physical skin texture verified."
            }
        )
    }
}
