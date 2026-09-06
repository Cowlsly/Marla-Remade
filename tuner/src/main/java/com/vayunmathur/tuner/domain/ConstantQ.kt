package com.vayunmathur.tuner.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Lowest CQT bin centre: C2. */
const val CQT_MIN_HZ: Double = 65.406391325149658

/** Three bins per semitone, so the dictionary can localise a partial that sits between notes. */
const val CQT_BINS_PER_OCTAVE: Int = 36

/** C2..B6 inclusive - five octaves, 180 bins. */
const val CQT_OCTAVES: Int = 5

/** Total bin count. */
const val CQT_BINS: Int = CQT_BINS_PER_OCTAVE * CQT_OCTAVES

/** Lowest note the note-salience stage can report (MIDI 36 = C2). */
const val LOWEST_NOTE_MIDI: Int = 36

/** Number of notes in the dictionary, C2..B6. */
const val NOTE_COUNT: Int = 60

/**
 * A multirate constant-Q transform, C2..B6 at 36 bins per octave (TUNER_SPEC C.2 step 1).
 *
 * A fixed-window FFT has constant frequency resolution but semitone spacing is proportional to
 * frequency, so one window long enough for the bass wastes 16x the time resolution it needs at
 * the top. The CQT fixes that; making it *multirate* fixes the cost. Each octave down halves
 * the sample rate, so every octave reuses one kernel bank of the same length instead of a bank
 * 16x longer at the bottom.
 *
 * Resolution limit, worth stating: at C2 the kernel spans 786 ms, giving a Hann main lobe about
 * 5.1 Hz wide against a 3.9 Hz semitone. Adjacent semitones below roughly C3 are only partly
 * separated, and NNLS deconvolves the rest imperfectly - close voicings down there smear.
 */
class ConstantQ(val sampleRate: Double) {
    /** Kernel bank shared by every octave, in that octave's own rate domain. */
    private val kernelReal: Array<DoubleArray>
    private val kernelImag: Array<DoubleArray>

    /** Samples of 48 kHz audio one transform consumes. */
    val requiredSamples: Int

    init {
        val q = 1.0 / (2.0.pow(1.0 / CQT_BINS_PER_OCTAVE) - 1.0)
        // The top octave sits at C6..B6, so its normalised centre frequencies are fixed
        // fractions of the input rate and every octave below reuses them after decimation.
        val topOctaveBase = CQT_MIN_HZ * 2.0.pow((CQT_OCTAVES - 1).toDouble())
        val real = ArrayList<DoubleArray>(CQT_BINS_PER_OCTAVE)
        val imag = ArrayList<DoubleArray>(CQT_BINS_PER_OCTAVE)
        var longest = 0
        for (j in 0 until CQT_BINS_PER_OCTAVE) {
            val nu = topOctaveBase * 2.0.pow(j.toDouble() / CQT_BINS_PER_OCTAVE) / sampleRate
            val length = (q / nu).roundToInt().coerceAtLeast(16)
            longest = maxOf(longest, length)
            val kr = DoubleArray(length)
            val ki = DoubleArray(length)
            var norm = 0.0
            for (m in 0 until length) {
                val w = 0.5 - 0.5 * cos(2.0 * PI * m / length)
                norm += w
                val angle = -2.0 * PI * nu * m
                kr[m] = w * cos(angle)
                ki[m] = w * sin(angle)
            }
            if (norm > 0.0) {
                for (m in 0 until length) {
                    kr[m] /= norm
                    ki[m] /= norm
                }
            }
            real += kr
            imag += ki
        }
        kernelReal = real.toTypedArray()
        kernelImag = imag.toTypedArray()
        requiredSamples = longest shl (CQT_OCTAVES - 1)
    }

    /**
     * Magnitudes of the [CQT_BINS] bins, taken at the end of [samples].
     *
     * Bin 0 is C2. Bin `i` has centre frequency `CQT_MIN_HZ * 2^(i/36)`.
     */
    fun magnitudes(samples: DoubleArray): DoubleArray {
        require(samples.size >= requiredSamples) { "need at least $requiredSamples samples" }
        val out = DoubleArray(CQT_BINS)
        var current = samples.copyOfRange(samples.size - requiredSamples, samples.size)
        for (octave in 0 until CQT_OCTAVES) {
            // Octave 0 is the top one; bin 0 of the output is the bottom of the range.
            val base = (CQT_OCTAVES - 1 - octave) * CQT_BINS_PER_OCTAVE
            for (j in 0 until CQT_BINS_PER_OCTAVE) {
                val kr = kernelReal[j]
                val ki = kernelImag[j]
                val start = current.size - kr.size
                if (start < 0) continue
                var accR = 0.0
                var accI = 0.0
                for (m in kr.indices) {
                    val v = current[start + m]
                    accR += v * kr[m]
                    accI += v * ki[m]
                }
                out[base + j] = kotlin.math.hypot(accR, accI)
            }
            if (octave < CQT_OCTAVES - 1) current = decimateByTwo(current)
        }
        return out
    }

    private companion object {
        /**
         * A 31-tap windowed-sinc low-pass at a quarter of the rate, used as the anti-alias
         * filter ahead of each 2:1 decimation.
         */
        val DECIMATION_TAPS: DoubleArray = run {
            val n = 31
            val centre = (n - 1) / 2
            val taps = DoubleArray(n)
            var sum = 0.0
            for (i in 0 until n) {
                val k = (i - centre).toDouble()
                val sinc = if (k == 0.0) 0.5 else sin(PI * 0.5 * k) / (PI * k)
                val window = 0.54 - 0.46 * cos(2.0 * PI * i / (n - 1))
                taps[i] = sinc * window
                sum += taps[i]
            }
            for (i in 0 until n) taps[i] /= sum
            taps
        }

        /** Filters and keeps every other sample. Only the retained outputs are computed. */
        fun decimateByTwo(input: DoubleArray): DoubleArray {
            val taps = DECIMATION_TAPS
            val centre = (taps.size - 1) / 2
            val out = DoubleArray(input.size / 2)
            for (i in out.indices) {
                val at = 2 * i
                var acc = 0.0
                for (k in taps.indices) {
                    val index = at - k + centre
                    if (index in input.indices) acc += taps[k] * input[index]
                }
                out[i] = acc
            }
            return out
        }
    }
}

/** CQT bin index (fractional) of a frequency. Bin 0 is [CQT_MIN_HZ]; may fall outside 0..179. */
internal fun cqtBinOf(hz: Double): Double =
    CQT_BINS_PER_OCTAVE * ln(hz / CQT_MIN_HZ) / ln(2.0)
