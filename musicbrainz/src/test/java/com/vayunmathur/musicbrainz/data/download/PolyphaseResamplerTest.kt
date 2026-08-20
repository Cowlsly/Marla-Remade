package com.vayunmathur.musicbrainz.data.download

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the one piece of DSP in the app.
 *
 * A resampler fails quietly: a wrong ratio is a pitch shift, a seam between chunks is a
 * click, a normalisation slip is a level change, and a filter that is too short lets
 * aliases fold into the audible band. None of that shows up in a build, and none of it can
 * be tested on a device from here, so it is all pinned down with synthetic signals: a DC
 * level for gain, tones for the frequency response, out-of-band tones for aliasing, and the
 * same input fed in different chunk sizes for the seams.
 */
class PolyphaseResamplerTest {

    private val outputRate = 48_000

    private fun resampler(inputRate: Int, channels: Int = 1) =
        PolyphaseResampler(inputRate, outputRate, channels)

    /** Runs a whole signal through in one call plus a flush, as the transcoder does. */
    private fun runAll(
        resampler: PolyphaseResampler,
        input: FloatArray,
        channels: Int = 1,
    ): FloatArray = resampler.process(input, input.size / channels) + resampler.flush()

    private fun sine(frequency: Double, rate: Int, frames: Int, amplitude: Double = 1.0) =
        FloatArray(frames) { (amplitude * sin(2.0 * PI * frequency * it / rate)).toFloat() }

    /**
     * Energy at [frequency] as a fraction of full scale, by the Goertzel algorithm.
     *
     * Cheaper and sharper than an FFT for the handful of bins these tests care about, and
     * it needs no power-of-two length.
     */
    private fun amplitudeAt(signal: FloatArray, frequency: Double, rate: Int, from: Int, to: Int): Double {
        val omega = 2.0 * PI * frequency / rate
        val coefficient = 2.0 * cos(omega)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until to) {
            val s = signal[i] + coefficient * s1 - s2
            s2 = s1
            s1 = s
        }
        val real = s1 - s2 * cos(omega)
        val imaginary = s2 * sin(omega)
        return 2.0 * sqrt(real * real + imaginary * imaginary) / (to - from)
    }

    private fun dbfs(amplitude: Double): Double =
        if (amplitude <= 0.0) -200.0 else 20.0 * log10(amplitude)

    private fun rms(signal: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += signal[i].toDouble() * signal[i]
        return sqrt(sum / (to - from))
    }

    // ------------------------------------------------------------------
    // Ratio
    // ------------------------------------------------------------------

    @Test
    fun `reduces every source rate to its lowest terms`() {
        val expected = mapOf(
            44_100 to (160 to 147),
            88_200 to (80 to 147),
            176_400 to (40 to 147),
            96_000 to (1 to 2),
            192_000 to (1 to 4),
            32_000 to (3 to 2),
        )
        for ((rate, ratio) in expected) {
            val resampler = resampler(rate)
            assertEquals(
                ratio,
                resampler.interpolation to resampler.decimation,
                "$rate Hz should resample by L/M = ${ratio.first}/${ratio.second}",
            )
        }
    }

    @Test
    fun `copies the input untouched when the rates already match`() {
        val input = FloatArray(1000) { Random(7).nextFloat() * 2f - 1f }
        val output = runAll(resampler(outputRate), input)
        assertEquals(input.size, output.size)
        assertTrue(input.contentEquals(output), "48 kHz should be a straight copy")
    }

    // ------------------------------------------------------------------
    // Gain and length
    // ------------------------------------------------------------------

    @Test
    fun `passes a DC level through at unity`() {
        val output = runAll(resampler(44_100), FloatArray(20_000) { 0.5f })
        // Skip the filter's ramp at either end, where the window straddles the silence
        // outside the signal.
        val settled = output.copyOfRange(2_000, output.size - 2_000)
        for (sample in settled) {
            assertTrue(
                abs(sample - 0.5f) < 1e-3f,
                "DC should come through at 0.5, not $sample - a phase is mis-normalised",
            )
        }
    }

    @Test
    fun `produces the frame count the ratio implies`() {
        for (rate in listOf(44_100, 88_200, 96_000, 176_400, 192_000, 32_000)) {
            val resampler = resampler(rate)
            val frames = 50_000
            val output = runAll(resampler, FloatArray(frames))
            val expected = frames.toLong() * resampler.interpolation / resampler.decimation
            assertTrue(
                abs(output.size - expected) <= 1,
                "$rate Hz: expected about $expected frames, got ${output.size}",
            )
        }
    }

    // ------------------------------------------------------------------
    // Frequency response
    // ------------------------------------------------------------------

    @Test
    fun `keeps a mid-band tone at its original level and adds nothing else`() {
        val input = sine(1_000.0, 44_100, 44_100, amplitude = 0.5)
        val output = runAll(resampler(44_100), input)
        val from = 2_000
        val to = output.size - 2_000

        val tone = amplitudeAt(output, 1_000.0, outputRate, from, to)
        assertTrue(
            abs(dbfs(tone) - dbfs(0.5)) < 0.1,
            "1 kHz should survive within 0.1 dB, got ${dbfs(tone)} dBFS",
        )

        // Everything that is not the tone: imaging that folded back in, plus arithmetic
        // noise. The images of a 1 kHz tone land around 4.9 kHz after decimation, so this
        // is a real measurement of the stopband and not just rounding.
        val total = rms(output, from, to)
        val residual = sqrt((total * total - tone * tone / 2.0).coerceAtLeast(0.0))
        assertTrue(
            dbfs(residual) < -70.0,
            "residual after removing the tone should be under -70 dBFS, got ${dbfs(residual)}",
        )
    }

    @Test
    fun `passes the top of the audible band and stops the rest`() {
        val frames = 44_100
        val passband = amplitudeAt(
            runAll(resampler(44_100), sine(18_000.0, 44_100, frames)),
            18_000.0,
            outputRate,
            2_000,
            frames * 160 / 147 - 2_000,
        )
        assertTrue(
            dbfs(passband) > -1.0,
            "18 kHz should pass near unity, got ${dbfs(passband)} dBFS",
        )

        val stopband = amplitudeAt(
            runAll(resampler(44_100), sine(22_040.0, 44_100, frames)),
            22_040.0,
            outputRate,
            2_000,
            frames * 160 / 147 - 2_000,
        )
        assertTrue(
            dbfs(stopband) < -40.0,
            "22 kHz should be well down, got ${dbfs(stopband)} dBFS",
        )
    }

    @Test
    fun `does not let a downsample fold ultrasonics into the audible band`() {
        // 30 kHz at 96 kHz and 40 kHz at 192 kHz are both above the 24 kHz output Nyquist,
        // so without an anti-alias filter they would reappear at 18 kHz and 8 kHz - the
        // second of those squarely audible.
        val cases = listOf(
            Triple(96_000, 30_000.0, 18_000.0),
            Triple(192_000, 40_000.0, 8_000.0),
        )
        for ((rate, tone, alias) in cases) {
            val output = runAll(resampler(rate), sine(tone, rate, rate))
            val from = 2_000
            val to = output.size - 2_000
            val leaked = amplitudeAt(output, alias, outputRate, from, to)
            assertTrue(
                dbfs(leaked) < -70.0,
                "$rate Hz: ${tone.toInt()} Hz should not alias to ${alias.toInt()} Hz, " +
                    "got ${dbfs(leaked)} dBFS",
            )
        }
    }

    // ------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------

    @Test
    fun `gives the same output whatever chunk sizes it is fed in`() {
        val input = FloatArray(100_000) { sin(it * 0.017).toFloat() * 0.8f }
        val whole = runAll(resampler(44_100), input)

        val random = Random(1234)
        val sizes = listOf(1, 7, 960, 4096)
        val chunked = resampler(44_100)
        val pieces = ArrayList<FloatArray>()
        var offset = 0
        while (offset < input.size) {
            val size = minOf(sizes[random.nextInt(sizes.size)], input.size - offset)
            pieces.add(chunked.process(input.copyOfRange(offset, offset + size), size))
            offset += size
        }
        pieces.add(chunked.flush())
        val joined = FloatArray(pieces.sumOf { it.size })
        var at = 0
        for (piece in pieces) {
            piece.copyInto(joined, at)
            at += piece.size
        }

        assertEquals(whole.size, joined.size, "chunking changed the output length")
        for (i in whole.indices) {
            assertTrue(
                abs(whole[i] - joined[i]) < 1e-6f,
                "chunk boundary at frame $i changed the output - that is an audible click",
            )
        }
    }

    @Test
    fun `keeps the channels apart`() {
        val frames = 20_000
        val input = FloatArray(frames * 2)
        val tone = sine(1_000.0, 44_100, frames)
        for (i in 0 until frames) input[i * 2] = tone[i]

        val output = runAll(resampler(44_100, channels = 2), input, channels = 2)
        var right = 0.0
        for (i in 1 until output.size step 2) right += output[i].toDouble() * output[i]
        assertTrue(
            dbfs(sqrt(right / (output.size / 2))) < -100.0,
            "a silent right channel must stay silent",
        )
    }
}
