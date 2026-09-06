package com.vayunmathur.tuner.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stage 2 and 3 of the pitch pipeline (TUNER_SPEC A.3): harmonic phase-slope refinement
 * followed by an inharmonicity-aware fit.
 *
 * The precision comes from measuring how far each harmonic's phase advances across a long
 * offset `D`, so the error scales as `fs / (2*pi*D)` rather than with an FFT bin width. Dividing
 * the nth harmonic's estimate by `n` then divides its error by `n` too, which is why the
 * harmonics are used at all - and on a phone microphone, where the fundamental of a low note is
 * barely present, they are the only signal there is.
 *
 * The stiffness fit matters on piano: real partials sit at `n*f0*sqrt(1 + B*n^2)`, so naively
 * averaging `f_n / n` biases the answer several cents sharp - exactly the error this design
 * exists to avoid.
 */
class PhaseSlope(
    val sampleRate: Double,
    /** Sub-window length, in samples. */
    val subWindow: Int,
    /** Separation between the two sub-windows, in samples. Precision scales with this. */
    val separation: Int,
) {
    private val window = DoubleArray(subWindow) { 0.5 - 0.5 * cos(2.0 * PI * it / subWindow) }

    /** Samples this estimator consumes from [offset] onwards. */
    val requiredSamples: Int get() = subWindow + separation

    /**
     * Refines [coarseHz] against the audio in [x] at [offset].
     *
     * Returns `null` when no harmonic survived the unwrap guard, which means stage 1 was wrong
     * rather than that the signal was bad.
     */
    fun refine(x: DoubleArray, offset: Int, coarseHz: Double): PhaseSlopeResult? {
        require(offset + requiredSamples <= x.size) { "estimator runs past the end of the buffer" }
        if (coarseHz <= 0.0) return null

        // The wrap is unambiguous only while |f - f_target| < fs / (2D); half of that is the
        // guard, because a larger implied correction means the harmonic was misassigned.
        val unwrapGuard = sampleRate / (4.0 * separation)
        val scale = sampleRate / (2.0 * PI * separation)
        val maxHarmonics = minOf(MAX_HARMONICS, floor(0.40 * sampleRate / coarseHz).toInt())
        if (maxHarmonics < 1) return null

        val partials = ArrayList<Partial>(maxHarmonics)
        for (n in 1..maxHarmonics) {
            val target = n * coarseHz
            if (target >= 0.45 * sampleRate) break

            var a1r = 0.0
            var a1i = 0.0
            var a2r = 0.0
            var a2i = 0.0
            val step = -2.0 * PI * target / sampleRate
            val kc = cos(step)
            val ks = sin(step)
            var cr = 1.0
            var ci = 0.0
            for (m in 0 until subWindow) {
                val w = window[m]
                val s1 = w * x[offset + m]
                val s2 = w * x[offset + m + separation]
                a1r += s1 * cr
                a1i += s1 * ci
                a2r += s2 * cr
                a2i += s2 * ci
                val nr = cr * kc - ci * ks
                ci = cr * ks + ci * kc
                cr = nr
                // The incremental rotator drifts off the unit circle over thousands of steps;
                // renormalising periodically is cheaper than a trig call per sample.
                if (m and 0xFF == 0xFF) {
                    val norm = hypot(cr, ci)
                    if (norm > 0.0) {
                        cr /= norm
                        ci /= norm
                    }
                }
            }

            val magnitude = sqrt(hypot(a1r, a1i) * hypot(a2r, a2i))
            if (magnitude <= 0.0) continue
            // The second window must be de-rotated by the phase the *target* frequency itself
            // advances over D samples. Without this the product carries arg(2*pi*f*D/fs) rather
            // than the offset from the target, and the estimate is wrong by whole wraps unless
            // f_t*D/fs happens to land on an integer. (TUNER_SPEC A.3 states the formula without
            // this term; taking it literally puts the fast band tens of cents out.)
            val referenceAngle = -2.0 * PI * target * separation / sampleRate
            val rr = cos(referenceAngle)
            val ri = sin(referenceAngle)
            val d2r = a2r * rr - a2i * ri
            val d2i = a2r * ri + a2i * rr
            // arg(X2 * conj(X1)), already wrapped to +/-pi by atan2.
            val crossR = d2r * a1r + d2i * a1i
            val crossI = d2i * a1r - d2r * a1i
            val deltaPhi = atan2(crossI, crossR)
            val correction = deltaPhi * scale
            if (abs(correction) > unwrapGuard) continue
            partials += Partial(n, target + correction, magnitude)
        }

        if (partials.isEmpty()) return null
        val strongest = partials.maxOf { it.magnitude }
        val usable = partials.filter { it.magnitude >= HARMONIC_FLOOR * strongest }
        if (usable.isEmpty()) return null
        return fit(usable)
    }

    /**
     * Weighted linear regression of `y = f_n / n` against `x = n^2`. The intercept is the ideal
     * fundamental and the slope is `f0 * B / 2`. Weights are `SNR * n^2`, with the squared
     * magnitude standing in for SNR, because the standard error of `f_n / n` scales as `1/n`.
     */
    private fun fit(partials: List<Partial>): PhaseSlopeResult {
        if (partials.size < MIN_PARTIALS_FOR_STIFFNESS) {
            var weight = 0.0
            var sum = 0.0
            for (p in partials) {
                val w = p.magnitude * p.magnitude * p.index * p.index
                weight += w
                sum += w * p.frequencyHz / p.index
            }
            val f0 = if (weight > 0.0) sum / weight else partials[0].frequencyHz / partials[0].index
            return PhaseSlopeResult(f0, 0.0, partials.size, stiffnessFitted = false)
        }

        var sw = 0.0
        var swx = 0.0
        var swy = 0.0
        var swxx = 0.0
        var swxy = 0.0
        for (p in partials) {
            val w = p.magnitude * p.magnitude * p.index * p.index
            val xv = (p.index * p.index).toDouble()
            val yv = p.frequencyHz / p.index
            sw += w
            swx += w * xv
            swy += w * yv
            swxx += w * xv * xv
            swxy += w * xv * yv
        }
        val denominator = sw * swxx - swx * swx
        if (denominator == 0.0) {
            return PhaseSlopeResult(swy / sw, 0.0, partials.size, stiffnessFitted = false)
        }
        val intercept = (swy * swxx - swx * swxy) / denominator
        val slope = (sw * swxy - swx * swy) / denominator
        val stiffness = if (intercept > 0.0) 2.0 * slope / intercept else 0.0
        return PhaseSlopeResult(intercept, stiffness, partials.size, stiffnessFitted = true)
    }

    private data class Partial(val index: Int, val frequencyHz: Double, val magnitude: Double)

    private companion object {
        /** Capped at 8: inharmonicity grows as n^2 and high partials of a plucked string die fast. */
        const val MAX_HARMONICS = 8

        /** Below four surviving partials the stiffness slope is not identifiable. */
        const val MIN_PARTIALS_FOR_STIFFNESS = 4

        /**
         * Partials this far below the strongest are noise, not signal. Their phase estimate is
         * meaningless, and the squared-magnitude weighting above keeps any that slip through
         * from moving the fit.
         */
        const val HARMONIC_FLOOR = 0.05
    }
}

/** The refined fundamental, plus the string-stiffness diagnostic that fell out of the fit. */
data class PhaseSlopeResult(
    val frequencyHz: Double,
    /** Inharmonicity coefficient `B`: ~1e-5 for a guitar string, 1e-4..1e-3 for piano bass. */
    val stiffness: Double,
    val harmonicsUsed: Int,
    val stiffnessFitted: Boolean,
)
