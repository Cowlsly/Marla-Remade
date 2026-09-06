package com.vayunmathur.tuner.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A round trip and a known transform, because a wrong FFT produces confident nonsense. */
class FftTest {
    @Test
    fun forwardThenInverseReturnsTheInput() {
        val size = 1024
        val fft = Fft(size)
        val original = Signals.sawtooth(220.0, size)
        val re = original.copyOf()
        val im = DoubleArray(size)
        fft.forward(re, im)
        fft.inverse(re, im)
        for (i in 0 until size) assertEquals(original[i], re[i], 1e-9)
    }

    @Test
    fun aPureBinLandsInExactlyThatBin() {
        val size = 512
        val fft = Fft(size)
        val bin = 37
        val re = DoubleArray(size) {
            kotlin.math.cos(2.0 * Math.PI * bin * it / size)
        }
        val im = DoubleArray(size)
        fft.forward(re, im)
        val magnitudes = DoubleArray(size) { kotlin.math.hypot(re[it], im[it]) }
        val peak = magnitudes.indices.maxBy { magnitudes[it] }
        assertTrue(peak == bin || peak == size - bin, "peak was at $peak")
        assertEquals(size / 2.0, magnitudes[bin], 1e-6)
        for (i in magnitudes.indices) {
            if (i != bin && i != size - bin) assertTrue(abs(magnitudes[i]) < 1e-9)
        }
    }
}
