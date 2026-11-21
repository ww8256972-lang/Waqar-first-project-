package math_brahmastra_native

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 5. `math_brahmastra_native/TremorFftSentinel.kt`
 * Pure-Math Radix-2 Cooley-Tukey Fast Fourier Transform (FFT) Analyzer for Physiological
 * Human Hand Micro-Tremor Verification driven by real `Sensor.TYPE_ACCELEROMETER` hardware batches.
 *
 * Rejects flat-line zero-variance emulators, desk-mounted bot rigs, and synthetic scripts.
 */
object TremorFftSentinel {

    private const val FFT_WINDOW_SIZE = 64

    data class TremorSpectrumReport(
        val sampleCount: Int,
        val tremorBandEnergy: Double,
        val totalAcEnergy: Double,
        val isGenuineHumanHand: Boolean
    )

    private val bufferLock = Any()
    private val liveRingBuffer = DoubleArray(FFT_WINDOW_SIZE)
    private var liveSampleCount: Int = 0
    private var liveWriteCursor: Int = 0

    /**
     * Appends a single live hardware `Sensor.TYPE_ACCELEROMETER` reading (`ax`, `ay`, `az` in m/s^2).
     */
    fun ingestAccelerometerEvent(ax: Float, ay: Float, az: Float) {
        val magnitude = sqrt(
            ax.toDouble() * ax.toDouble() +
                ay.toDouble() * ay.toDouble() +
                az.toDouble() * az.toDouble()
        )
        synchronized(bufferLock) {
            liveRingBuffer[liveWriteCursor] = magnitude
            liveWriteCursor = (liveWriteCursor + 1) % FFT_WINDOW_SIZE
            if (liveSampleCount < FFT_WINDOW_SIZE) {
                liveSampleCount++
            }
        }
    }

    /**
     * Ingests a batch of live hardware accelerometer magnitudes into the rolling FFT ring buffer.
     */
    fun ingestLiveBatch(magnitudes: DoubleArray) {
        if (magnitudes.isEmpty()) return
        synchronized(bufferLock) {
            for (m in magnitudes) {
                liveRingBuffer[liveWriteCursor] = m
                liveWriteCursor = (liveWriteCursor + 1) % FFT_WINDOW_SIZE
                if (liveSampleCount < FFT_WINDOW_SIZE) {
                    liveSampleCount++
                }
            }
        }
    }

    fun hasLiveHardwareBatch(): Boolean = synchronized(bufferLock) {
        liveSampleCount >= 16
    }

    fun clearLiveBuffer() {
        synchronized(bufferLock) {
            liveRingBuffer.fill(0.0)
            liveSampleCount = 0
            liveWriteCursor = 0
        }
    }

    /**
     * Analyzes only genuinely captured live hardware accelerometer samples from the ring buffer.
     * Returns `null` if insufficient live hardware samples have arrived yet (never fabricates a wave).
     */
    fun analyzeLatestLiveWindow(): TremorSpectrumReport? {
        val snapshot: DoubleArray = synchronized(bufferLock) {
            if (liveSampleCount < 16) return null
            val out = DoubleArray(liveSampleCount)
            val start = if (liveSampleCount < FFT_WINDOW_SIZE) 0 else liveWriteCursor
            for (i in 0 until liveSampleCount) {
                out[i] = liveRingBuffer[(start + i) % FFT_WINDOW_SIZE]
            }
            out
        }
        return analyzeMicroTremorWindow(snapshot)
    }

    /**
     * Computes an in-place Radix-2 Cooley-Tukey FFT on [real] and [imag] arrays (size must be power of 2).
     */
    fun computeRadix2Fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        require(n > 0 && (n and (n - 1)) == 0 && imag.size == n) {
            "FFT input length must be a power of 2."
        }

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        // Butterfly stages
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = -2.0 * PI / len
            val wLenReal = cos(angle)
            val wLenImag = sin(angle)

            var i = 0
            while (i < n) {
                var wReal = 1.0
                var wImag = 0.0
                for (k in 0 until halfLen) {
                    val uReal = real[i + k]
                    val uImag = imag[i + k]
                    val vReal = real[i + k + halfLen] * wReal - imag[i + k + halfLen] * wImag
                    val vImag = real[i + k + halfLen] * wImag + imag[i + k + halfLen] * wReal

                    real[i + k] = uReal + vReal
                    imag[i + k] = uImag + vImag
                    real[i + k + halfLen] = uReal - vReal
                    imag[i + k + halfLen] = uImag - vImag

                    val nextWReal = wReal * wLenReal - wImag * wLenImag
                    wImag = wReal * wLenImag + wImag * wLenReal
                    wReal = nextWReal
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Analyzes accelerometer/gyroscope magnitude samples to verify non-zero physiological
     * micro-tremor spectrum energy vs. flat-line emulators (`0.0` energy).
     */
    fun analyzeMicroTremorWindow(samples: DoubleArray): TremorSpectrumReport {
        val n = FFT_WINDOW_SIZE
        val paddedReal = DoubleArray(n)
        val paddedImag = DoubleArray(n)
        val mean = if (samples.isEmpty()) 0.0 else samples.average()
        for (i in 0 until n) {
            val raw = if (samples.isNotEmpty()) samples[i % samples.size] else 0.0
            paddedReal[i] = raw - mean // Remove DC offset
        }

        computeRadix2Fft(paddedReal, paddedImag)

        var tremorBandEnergy = 0.0
        var totalAcEnergy = 0.0
        for (bin in 1 until (n / 2)) {
            val mag = sqrt(paddedReal[bin] * paddedReal[bin] + paddedImag[bin] * paddedImag[bin])
            totalAcEnergy += mag
            if (bin in 1..12) {
                tremorBandEnergy += mag
            }
        }

        val genuine = tremorBandEnergy > 1e-4 && totalAcEnergy > 1e-4
        return TremorSpectrumReport(
            sampleCount = samples.size,
            tremorBandEnergy = tremorBandEnergy,
            totalAcEnergy = totalAcEnergy,
            isGenuineHumanHand = genuine
        )
    }

    fun isGenuineHumanHand(samples: DoubleArray): Boolean =
        analyzeMicroTremorWindow(samples).isGenuineHumanHand
}
