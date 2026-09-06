package com.vayunmathur.tuner.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * An in-place radix-2 complex FFT with precomputed twiddle factors.
 *
 * Every transform this app needs is a power of two (unlike Whisper's 400-point mixed-radix
 * front end in `:speech`), so the plain iterative Cooley-Tukey butterfly is enough. Twiddles
 * and the bit-reversal permutation are precomputed per size because the pitch pipeline runs
 * the same size ~94 times a second.
 *
 * `Double` throughout, not `Float`: the YIN difference function subtracts two nearly equal
 * sums of squares, and the cancellation there is what sets the noise floor of the coarse
 * period estimate.
 */
class Fft(val size: Int) {
    private val cosTable = DoubleArray(size / 2)
    private val sinTable = DoubleArray(size / 2)
    private val reversed = IntArray(size)

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two: $size" }
        for (k in 0 until size / 2) {
            val angle = 2.0 * PI * k / size
            cosTable[k] = cos(angle)
            sinTable[k] = sin(angle)
        }
        var bits = 0
        while (1 shl bits < size) bits++
        for (i in 0 until size) {
            var value = i
            var out = 0
            for (b in 0 until bits) {
                out = (out shl 1) or (value and 1)
                value = value shr 1
            }
            reversed[i] = out
        }
    }

    /** Forward transform of [re]/[im], in place. Both arrays must be [size] long. */
    fun forward(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size) { "arrays must be $size long" }
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var base = 0
            while (base < size) {
                var k = 0
                for (j in base until base + half) {
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val p = j + half
                    val vr = re[p] * wr - im[p] * wi
                    val vi = re[p] * wi + im[p] * wr
                    re[p] = re[j] - vr
                    im[p] = im[j] - vi
                    re[j] += vr
                    im[j] += vi
                    k += step
                }
                base += len
            }
            len = len shl 1
        }
    }

    /** Inverse transform of [re]/[im], in place, scaled by `1/size`. */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) im[i] = -im[i]
        forward(re, im)
        val scale = 1.0 / size
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= -scale
        }
    }
}
