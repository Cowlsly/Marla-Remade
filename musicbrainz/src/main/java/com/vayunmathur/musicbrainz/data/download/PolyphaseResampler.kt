package com.vayunmathur.musicbrainz.data.download

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Resamples interleaved float PCM between two rates by a rational ratio.
 *
 * Every download is encoded to Opus, and Opus only accepts 8/12/16/24/48 kHz - it cannot
 * encode 44.1 kHz at all - so a CD-rate or hi-res source has to be resampled to 48 kHz
 * before it reaches the encoder. That makes this the one piece of DSP in the app, and the
 * platform ships no resampler to borrow.
 *
 * The design is the textbook one: conceptually insert `L - 1` zeros between input frames,
 * low-pass, then keep every `M`th sample. Nothing is materialised at the `L * inputRate`
 * intermediate rate; the prototype filter is decomposed into `L` phases so each output
 * frame costs one dot product of [tapsPerPhase] taps per channel. `L` and `M` come from
 * reducing the two rates by their gcd, so 44.1 kHz gives 160/147, 96 kHz gives 1/2 and
 * 192 kHz gives 1/4 with no ratio table.
 *
 * ### Filter
 *
 * One Kaiser-windowed sinc, cut off just under half the *lower* of the two rates: that
 * single expression buys anti-imaging when upsampling and anti-aliasing when downsampling,
 * because in both cases the band that has to survive is bounded by the lower rate.
 *
 * The length is chosen to hit a fixed transition width rather than being a fixed tap count:
 * a fixed count would leave a decimate-by-4 filter four times too short and audibly dull
 * the top octave. The passband reaches [PASSBAND_FRACTION] of the lower rate and the
 * stopband starts at its Nyquist, so a 44.1 kHz source is flat to 19.3 kHz and 80 dB down
 * by 22.05 kHz. Stopping short of 20 kHz is deliberate: the transition band has to fit
 * between the passband edge and the Nyquist it protects, and every extra kilohertz of
 * flatness up there costs taps in proportion. Nothing audible is lost, and the 48 kHz Opus
 * encode that follows discards everything above roughly 20 kHz anyway.
 *
 * ### Streaming
 *
 * [process] emits only the output frames whose whole tap window has arrived and keeps the
 * rest of the window as history, so the chunk sizes a caller happens to use cannot change
 * a single output sample; [flush] drains the tail at the end of the track. The window is
 * centred on the output instant, which cancels the filter's group delay, so the output is
 * time-aligned with the input to within a fraction of a sample.
 */
internal class PolyphaseResampler(
    private val inputRate: Int,
    private val outputRate: Int,
    private val channels: Int,
) {
    /** `L`: how many output samples the intermediate rate carries per input sample. */
    val interpolation: Int

    /** `M`: how many intermediate samples are dropped per output sample. */
    val decimation: Int

    /** Taps per phase, i.e. how many input frames each output frame is a weighted sum of. */
    val tapsPerPhase: Int

    private val passthrough = inputRate == outputRate

    /** `[phase][tap]`, each phase normalised to sum to 1 so a DC input passes at unity. */
    private val coefficients: Array<FloatArray>

    /**
     * The input frames still needed as tap history, interleaved, oldest first.
     * `history[0]` is the frame at absolute index [historyStart].
     */
    private var history = FloatArray(0)
    private var historyFrames = 0
    private var historyStart = 0L

    /** Absolute count of input frames handed to [process] so far. */
    private var framesIn = 0L

    /** Absolute index of the newest input frame the next output frame reads. */
    private var window = 0L

    /** `(outputIndex * M) mod L`, advanced by `M` and wrapped by `L`. */
    private var phase = 0

    init {
        require(inputRate > 0 && outputRate > 0) { "rates must be positive" }
        require(channels in 1..MAX_CHANNELS) { "channels must be 1..$MAX_CHANNELS" }
        val divisor = gcd(inputRate, outputRate)
        interpolation = outputRate / divisor
        decimation = inputRate / divisor
        if (passthrough) {
            tapsPerPhase = 0
            coefficients = emptyArray()
        } else {
            tapsPerPhase = tapsPerPhaseFor(interpolation, decimation)
            val cutoffFraction = (PASSBAND_FRACTION + STOPBAND_FRACTION) / 2.0
            coefficients = buildCoefficients(
                interpolation,
                tapsPerPhase,
                cutoff = cutoffFraction * min(inputRate, outputRate) /
                    (interpolation.toDouble() * inputRate),
            )
        }
        window = (tapsPerPhase / 2).toLong()
    }

    /**
     * Resamples [frames] interleaved frames from the front of [input].
     *
     * Returns exactly the output frames whose tap window is complete, which is why calling
     * this once with a whole track and calling it repeatedly with arbitrary chunks give
     * byte-identical results.
     */
    fun process(input: FloatArray, frames: Int): FloatArray {
        if (frames <= 0) return EMPTY
        if (passthrough) return input.copyOf(frames * channels)
        appendHistory(input, frames)
        framesIn += frames
        // The newest frame the next output needs must already have arrived.
        return emit(available = framesIn, limit = framesIn)
    }

    /**
     * Drains the outputs still owed for the input already supplied, reading past the end of
     * the stream as silence. Called once, at the end of the track.
     */
    fun flush(): FloatArray {
        if (passthrough) return EMPTY
        return emit(available = Long.MAX_VALUE, limit = framesIn)
    }

    /**
     * Produces output frames while the window's newest frame is below [available] and the
     * output still corresponds to an input frame that exists ([limit]).
     */
    private fun emit(available: Long, limit: Long): FloatArray {
        val taps = tapsPerPhase
        var count = 0
        // The output index reading window `w` corresponds to input frame `w - taps/2`, and
        // exists while that is below `limit`. Counting first keeps the result a single
        // exact allocation, and costs only integer arithmetic.
        run {
            var w = window
            var p = phase
            while (w - taps / 2 < limit && w < available) {
                count++
                val advanced = p + decimation
                w += advanced / interpolation
                p = advanced % interpolation
            }
        }
        if (count == 0) return EMPTY

        val out = FloatArray(count * channels)
        val stereo = channels == 2
        var outIndex = 0
        repeat(count) {
            val phaseTaps = coefficients[phase]
            // Taps whose frame falls outside the stream contribute nothing, so clamp the
            // range instead of bounds-checking every one. Inside the range the history is
            // interleaved with a stride of `channels`, so one coefficient load feeds both
            // channel accumulators.
            val newest = (window - historyStart).toInt()
            val from = maxOf(0, newest - historyFrames + 1)
            val to = minOf(taps - 1, newest)
            var left = 0f
            var right = 0f
            var sample = (newest - from) * channels
            for (k in from..to) {
                val coefficient = phaseTaps[k]
                left += coefficient * history[sample]
                if (stereo) right += coefficient * history[sample + 1]
                sample -= channels
            }
            out[outIndex++] = left
            if (stereo) out[outIndex++] = right

            val advanced = phase + decimation
            window += advanced / interpolation
            phase = advanced % interpolation
        }
        dropHistoryBefore(window - taps + 1)
        return out
    }

    private fun appendHistory(input: FloatArray, frames: Int) {
        val needed = (historyFrames + frames) * channels
        if (history.size < needed) {
            history = history.copyOf(maxOf(needed, history.size * 2, INITIAL_HISTORY_FRAMES * channels))
        }
        System.arraycopy(input, 0, history, historyFrames * channels, frames * channels)
        historyFrames += frames
    }

    private fun dropHistoryBefore(frame: Long) {
        val drop = (frame - historyStart).coerceAtMost(historyFrames.toLong()).toInt()
        if (drop <= 0) return
        val remaining = historyFrames - drop
        System.arraycopy(history, drop * channels, history, 0, remaining * channels)
        historyFrames = remaining
        historyStart += drop
    }

    private companion object {
        val EMPTY = FloatArray(0)

        /** The Opus encoder takes at most stereo, so nothing wider ever reaches this. */
        const val MAX_CHANNELS = 2

        const val INITIAL_HISTORY_FRAMES = 4096

        /**
         * The passband edge and the stopband edge, as fractions of the lower of the two
         * rates. The stopband starts at that rate's Nyquist, which is the frequency above
         * which imaging or aliasing folds into the output band.
         */
        const val PASSBAND_FRACTION = 0.4375
        const val STOPBAND_FRACTION = 0.5

        /** Stopband attenuation the length and the Kaiser window are designed for. */
        const val STOPBAND_DB = 80.0

        fun gcd(a: Int, b: Int): Int {
            var x = a
            var y = b
            while (y != 0) {
                val t = x % y
                x = y
                y = t
            }
            return x
        }

        /**
         * Kaiser's length estimate, `N = (A - 8) / (2.285 * transition in rad/sample)`,
         * expressed per phase.
         *
         * The transition width in Hz works out independent of `L`, so this reduces to a
         * constant for any upsample and scales with `M / L` for a downsample - which is
         * exactly the wanted behaviour, since decimating by four needs four times the taps
         * to hold the same transition. The cap is what stops a 192 kHz source, the only
         * ratio that reaches it, from costing four times a CD-rate one for a difference
         * that is entirely above 18 kHz.
         */
        fun tapsPerPhaseFor(interpolation: Int, decimation: Int): Int {
            val transition = STOPBAND_FRACTION - PASSBAND_FRACTION
            val perPhase = (STOPBAND_DB - 8.0) *
                maxOf(1.0, decimation.toDouble() / interpolation) /
                (2.285 * 2.0 * PI * transition)
            // Even, so the window centres cleanly on the output instant.
            val taps = 2 * ceil(perPhase / 2.0).toInt()
            return taps.coerceIn(MIN_TAPS_PER_PHASE, MAX_TAPS_PER_PHASE)
        }

        const val MIN_TAPS_PER_PHASE = 16
        const val MAX_TAPS_PER_PHASE = 192

        /**
         * Splits one prototype low-pass into `L` phases.
         *
         * Phase `p` holds `h[p], h[p + L], h[p + 2L], ...`, which is the subset of taps that
         * lands on a real input frame rather than on one of the inserted zeros. Each phase
         * is normalised to sum to 1 so DC passes at exactly unity, which also makes the
         * `20 * log10(L)` gain blunder impossible.
         */
        fun buildCoefficients(interpolation: Int, tapsPerPhase: Int, cutoff: Double): Array<FloatArray> {
            val length = tapsPerPhase * interpolation
            val centre = (length - 1) / 2.0
            val beta = kaiserBeta(STOPBAND_DB)
            val i0Beta = besselI0(beta)
            val prototype = DoubleArray(length) { n ->
                val x = n - centre
                val window = besselI0(beta * sqrt(1.0 - (2.0 * n / (length - 1) - 1.0).let { it * it })) / i0Beta
                2.0 * cutoff * sinc(2.0 * cutoff * x) * window
            }
            return Array(interpolation) { phase ->
                val taps = DoubleArray(tapsPerPhase) { k -> prototype[phase + k * interpolation] }
                val sum = taps.sum()
                FloatArray(tapsPerPhase) { k -> (taps[k] / sum).toFloat() }
            }
        }

        /** Kaiser's β for a given stopband attenuation, for the 21..50 dB and >50 dB cases. */
        fun kaiserBeta(attenuationDb: Double): Double = when {
            attenuationDb > 50 -> 0.1102 * (attenuationDb - 8.7)
            attenuationDb >= 21 -> 0.5842 * Math.pow(attenuationDb - 21, 0.4) +
                0.07886 * (attenuationDb - 21)
            else -> 0.0
        }

        fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        /** Modified Bessel function of the first kind, order zero, by its power series. */
        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val half = x / 2.0
            var k = 1
            while (k < 40) {
                term *= half * half / (k * k)
                sum += term
                if (term < sum * 1e-16) break
                k++
            }
            return sum
        }
    }
}
