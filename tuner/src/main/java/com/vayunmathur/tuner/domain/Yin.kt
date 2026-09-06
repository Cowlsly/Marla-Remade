package com.vayunmathur.tuner.domain

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Stage 1 of the pitch pipeline: YIN, coarse and robust (TUNER_SPEC A.3).
 *
 * Its job is to pin the octave and hand [PhaseSlope] a target within about +/-3 cents, plus an
 * aperiodicity number the caller can gate on. It is deliberately *not* the source of the
 * displayed frequency - parabolic interpolation of the difference function tops out around
 * 0.3 cents and degrades fast with noise.
 *
 * The difference function is computed through the FFT rather than the textbook O(W*tau) double
 * loop, using the exact half-window form
 *
 *     d(tau) = sum_{j<H} x[j]^2 + sum_{j=tau}^{tau+H} x[j]^2 - 2 * sum_{j<H} x[j]*x[j+tau]
 *
 * with `H = W/2`, so d(0) is exactly zero and no windowing bias is introduced.
 */
class Yin(val windowSize: Int, val sampleRate: Double) {
    private val half = windowSize / 2
    private val fft = Fft(2 * windowSize)
    private val re = DoubleArray(2 * windowSize)
    private val im = DoubleArray(2 * windowSize)
    private val reB = DoubleArray(2 * windowSize)
    private val imB = DoubleArray(2 * windowSize)
    private val cumulativePower = DoubleArray(windowSize + 1)
    private val difference = DoubleArray(half)
    private val normalised = DoubleArray(half)

    init {
        require(windowSize >= 64 && windowSize and (windowSize - 1) == 0) {
            "window must be a power of two >= 64"
        }
    }

    /**
     * Estimates the period of [x] starting at [offset].
     *
     * [minHz]/[maxHz] clamp the lag search to the selected instrument's range, which removes a
     * whole class of octave errors for free. Returns `null` only when the search range is empty.
     */
    fun analyse(x: DoubleArray, offset: Int, minHz: Double, maxHz: Double): YinResult? {
        require(offset + windowSize <= x.size) { "window runs past the end of the buffer" }

        var energy = 0.0
        for (j in 0 until windowSize) {
            val v = x[offset + j]
            energy += v * v
            cumulativePower[j + 1] = energy
        }
        cumulativePower[0] = 0.0
        val rms = sqrt(energy / windowSize)

        re.fill(0.0)
        im.fill(0.0)
        reB.fill(0.0)
        imB.fill(0.0)
        for (j in 0 until half) re[j] = x[offset + j]
        for (j in 0 until windowSize) reB[j] = x[offset + j]
        fft.forward(re, im)
        fft.forward(reB, imB)
        // conj(A) * B, then inverse: element tau is sum_j a[j]*b[j+tau].
        for (k in re.indices) {
            val ar = re[k]
            val ai = im[k]
            val br = reB[k]
            val bi = imB[k]
            re[k] = ar * br + ai * bi
            im[k] = ar * bi - ai * br
        }
        fft.inverse(re, im)

        val headPower = cumulativePower[half]
        for (tau in 0 until half) {
            val tailPower = cumulativePower[tau + half] - cumulativePower[tau]
            difference[tau] = (headPower + tailPower - 2.0 * re[tau]).coerceAtLeast(0.0)
        }

        normalised[0] = 1.0
        var running = 0.0
        for (tau in 1 until half) {
            running += difference[tau]
            normalised[tau] = if (running > 0.0) difference[tau] * tau / running else 1.0
        }

        val tauMin = ceil(sampleRate / maxHz).toInt().coerceAtLeast(2)
        val tauMax = floor(sampleRate / minHz).toInt().coerceAtMost(half - 2)
        if (tauMin >= tauMax) return null

        // YIN step 4: the *first* local minimum under the absolute threshold, not the global
        // one. This single choice is what prevents the classic octave-too-low error.
        var chosen = -1
        var tau = tauMin
        while (tau <= tauMax) {
            if (normalised[tau] < ABSOLUTE_THRESHOLD) {
                while (tau + 1 <= tauMax && normalised[tau + 1] < normalised[tau]) tau++
                chosen = tau
                break
            }
            tau++
        }
        if (chosen < 0) {
            var best = tauMin
            for (t in tauMin..tauMax) if (normalised[t] < normalised[best]) best = t
            chosen = best
        }

        val refined = parabolicMinimum(normalised, chosen)
        if (refined <= 0.0) return null
        return YinResult(
            periodSamples = refined,
            frequencyHz = sampleRate / refined,
            aperiodicity = normalised[chosen],
            rms = rms,
            belowThreshold = normalised[chosen] < ABSOLUTE_THRESHOLD,
        )
    }

    private companion object {
        /** YIN's absolute threshold. Below this, a lag counts as a genuine period candidate. */
        const val ABSOLUTE_THRESHOLD = 0.15
    }
}

/** What [Yin] found in one frame. */
data class YinResult(
    val periodSamples: Double,
    val frequencyHz: Double,
    /** `d'(tau)` at the chosen lag: 0 is perfectly periodic, 1 is noise. */
    val aperiodicity: Double,
    val rms: Double,
    val belowThreshold: Boolean,
)

/** Parabolic vertex of `y` around index [i], returned as a fractional index. */
internal fun parabolicMinimum(y: DoubleArray, i: Int): Double {
    if (i <= 0 || i >= y.size - 1) return i.toDouble()
    val a = y[i - 1]
    val b = y[i]
    val c = y[i + 1]
    val denominator = a - 2.0 * b + c
    if (denominator == 0.0) return i.toDouble()
    val shift = 0.5 * (a - c) / denominator
    return if (shift > -1.0 && shift < 1.0) i + shift else i.toDouble()
}
