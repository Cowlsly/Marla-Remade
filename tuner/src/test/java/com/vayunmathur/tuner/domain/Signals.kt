package com.vayunmathur.tuner.domain

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic signal generators for the DSP tests.
 *
 * A tuner cannot be validated end to end without a real instrument in a real room, but every
 * stage of it *can* be validated against a signal whose true frequency is known exactly. That is
 * what these produce - and it is the difference between a measured claim and a hopeful one.
 */
internal object Signals {
    const val SAMPLE_RATE = 48_000.0

    /** A pure sine at [hz], with a fixed phase offset so no test accidentally starts at zero. */
    fun sine(hz: Double, count: Int, amplitude: Double = 0.5, phase: Double = 0.7): DoubleArray =
        DoubleArray(count) { amplitude * sin(2.0 * PI * hz * it / SAMPLE_RATE + phase) }

    /**
     * A harmonic stack with geometric decay - the crude shape of a plucked string, and enough to
     * exercise the fundamental-is-missing case when [amplitudes] starts at zero.
     */
    fun harmonic(
        hz: Double,
        count: Int,
        amplitudes: List<Double>,
        stiffness: Double = 0.0,
    ): DoubleArray {
        val out = DoubleArray(count)
        amplitudes.forEachIndexed { index, amplitude ->
            if (amplitude == 0.0) return@forEachIndexed
            val n = index + 1
            // Real strings are stiff: partials sit at n*f0*sqrt(1 + B*n^2), not at n*f0.
            val partial = n * hz * sqrt(1.0 + stiffness * n * n)
            val phase = 0.3 * n
            for (i in 0 until count) {
                out[i] += amplitude * sin(2.0 * PI * partial * i / SAMPLE_RATE + phase)
            }
        }
        return out
    }

    /** A sawtooth, which is every harmonic at `1/n` - the worst case for octave errors. */
    fun sawtooth(hz: Double, count: Int, partials: Int = 20): DoubleArray =
        harmonic(hz, count, List(partials) { 0.5 / (it + 1) })

    /** Adds white noise at the requested signal-to-noise ratio in decibels. */
    fun withNoise(signal: DoubleArray, snrDb: Double, seed: Int = 42): DoubleArray {
        var power = 0.0
        for (v in signal) power += v * v
        power /= signal.size
        val noisePower = power / 10.0.pow(snrDb / 10.0)
        val sigma = sqrt(noisePower)
        val random = Random(seed)
        return DoubleArray(signal.size) { signal[it] + sigma * gaussian(random) }
    }

    /** White noise on its own. */
    fun noise(count: Int, sigma: Double = 0.3, seed: Int = 7): DoubleArray {
        val random = Random(seed)
        return DoubleArray(count) { sigma * gaussian(random) }
    }

    /**
     * Pink noise: white through Paul Kellett's three-pole approximation of a `1/f` filter.
     *
     * Worth testing separately from white because it is the harder case in both directions - it
     * is the shape real room noise actually has, and its falling spectrum makes it look less
     * flat, and so more tonal, than white noise does.
     */
    fun pinkNoise(count: Int, sigma: Double = 0.3, seed: Int = 11): DoubleArray {
        val random = Random(seed)
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        return DoubleArray(count) {
            val white = gaussian(random)
            b0 = 0.99765 * b0 + white * 0.0990460
            b1 = 0.96300 * b1 + white * 0.2965164
            b2 = 0.57000 * b2 + white * 1.0526913
            sigma * (b0 + b1 + b2 + white * 0.1848) / PINK_GAIN
        }
    }

    /** Sums several harmonic stacks, i.e. a chord. */
    fun chord(midis: List<Int>, count: Int, reference: PitchReference = PitchReference()): DoubleArray {
        val out = DoubleArray(count)
        for (midi in midis) {
            val partial = harmonic(
                reference.referenceHz(midi),
                count,
                List(8) { 0.4 * 0.75.pow(it) },
            )
            for (i in 0 until count) out[i] += partial[i]
        }
        return out
    }

    /**
     * A chord as it actually reaches the microphone: percussive attack, tonal sustain,
     * exponential decay, over a continuous room floor.
     *
     * [chord] is sustained and noiseless, which is the shape the tonality gate was originally
     * measured against. Nothing a player produces looks like that. The three phases here each
     * present the gate with a different spectrum - the attack is broadband, the sustain is
     * tonal, and the decay slides from one to the other as the strings fall towards the floor -
     * and it is the transition through that middle region, not the sustain, that decides
     * whether the name on screen is stable.
     *
     * [attackSeconds] is the pick/nail noise: broadband, loud, and over quickly.
     * [decaySeconds] is the 1/e time of the tonal part.
     * [roomSigma] is a pink floor standing in for a real room and the microphone's own noise.
     */
    fun strum(
        midis: List<Int>,
        count: Int,
        attackSeconds: Double = 0.02,
        decaySeconds: Double = 1.2,
        roomSigma: Double = 0.004,
        seed: Int = 23,
        reference: PitchReference = PitchReference(),
    ): DoubleArray {
        val tonal = chord(midis, count, reference)
        val attack = noise(count, sigma = 1.0, seed = seed + 1)
        val room = pinkNoise(count, sigma = roomSigma, seed = seed + 2)
        val attackSamples = (attackSeconds * SAMPLE_RATE).coerceAtLeast(1.0)
        return DoubleArray(count) { i ->
            val t = i / SAMPLE_RATE
            val body = tonal[i] * exp(-t / decaySeconds)
            // Strings do not all speak at once, so the attack overlaps the start of the sustain
            // rather than preceding it.
            val burst = attack[i] * 0.35 * exp(-i / attackSamples)
            body + burst + room[i]
        }
    }

    /**
     * [strum] cut into the hops the chord pipeline actually consumes: [window] samples ending at
     * each successive multiple of [hop], so frame `k` is what the detector sees `k * hop`
     * samples after the pluck.
     */
    fun hops(signal: DoubleArray, window: Int, hop: Int): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var end = window
        while (end <= signal.size) {
            out += signal.copyOfRange(end - window, end)
            end += hop
        }
        return out
    }

    private fun gaussian(random: Random): Double {
        // Box-Muller. Guard the log against an exact zero draw.
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * PI * u2)
    }

    /** Brings the three-pole cascade back to roughly unit variance. */
    private const val PINK_GAIN = 3.5
}
