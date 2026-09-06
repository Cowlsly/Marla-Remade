package com.vayunmathur.tuner.domain

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Streams a moving window through the analyser the way the ViewModel does, rather than calling
 * [YinPhaseSlopeAnalyzer.analyse] repeatedly on one static buffer.
 *
 * That distinction is the whole point of this class. Every other pitch test hands the analyser a
 * clean, sustained, perfectly periodic tone, which is accepted on every frame - so the adaptive
 * noise floor, which only updates on *rejected* frames, is never exercised at all and sits at its
 * cold-start value for the entire suite. A real instrument rejects constantly: attacks, decay
 * tails, gaps between notes, and strums (polyphonic, so YIN correctly calls them aperiodic). The
 * bug this guards against was a gate that latched shut after a few seconds of exactly that and
 * never reopened, which is invisible to a static-buffer test and total on a real device.
 */
class StreamingDetectionTest {

    private val reference = PitchReference()

    private fun gaussian(random: Random): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * PI * u2)
    }

    private fun room(count: Int, rms: Double, seed: Int) =
        DoubleArray(count) { rms * gaussian(Random(seed + it)) }

    /** One pluck: eight slightly inharmonic partials under an exponential decay. */
    private fun pluck(out: DoubleArray, at: Int, hz: Double, amplitude: Double, decay: Double) {
        val amplitudes = List(8) { 0.45 * 0.78.pow(it) }
        var i = at
        while (i < out.size) {
            val t = (i - at) / RATE
            val envelope = exp(-t / decay)
            if (envelope < 1e-4) break
            var v = 0.0
            amplitudes.forEachIndexed { index, a ->
                val n = index + 1
                v += a * sin(2.0 * PI * n * hz * sqrt(1.0 + 1e-4 * n * n) * t + 0.3 * n)
            }
            out[i] += amplitude * envelope * v
            i++
        }
    }

    private class Outcome(val counts: Map<String, Int>, val frames: Int) {
        val detected: Int get() = counts.entries.sumOf { if (it.key.startsWith("OK:")) it.value else 0 }
        val tooQuiet: Int get() = counts["TOO_QUIET"] ?: 0
        override fun toString() = "frames=$frames $counts"
    }

    /** Runs the moving 8192-sample window at the ViewModel's 512-sample hop. */
    private fun stream(signal: DoubleArray, from: Double = 0.0): Outcome {
        val analyzer = YinPhaseSlopeAnalyzer(RATE)
        val window = DoubleArray(PitchBand.LOW.windowSize)
        val counts = LinkedHashMap<String, Int>()
        var frames = 0
        var start = 0
        val reportFrom = (from * RATE).toInt()
        while (start + window.size <= signal.size) {
            signal.copyInto(window, 0, start, start + window.size)
            val frame = analyzer.analyse(window, UKE_MIN_HZ, UKE_MAX_HZ)
            if (start >= reportFrom) {
                val key = when (frame) {
                    is PitchFrame.Detected ->
                        "OK:" + spell(reference.nearestMidi(frame.estimate.frequencyHz))
                    is PitchFrame.Silent -> frame.reason.name
                }
                counts[key] = (counts[key] ?: 0) + 1
                frames++
            }
            start += HOP
        }
        return Outcome(counts, frames)
    }

    @Test
    fun aQuietUkuleleIsTrackedForAsLongAsItIsPlayed() {
        // -40 dBFS is roughly a ukulele a metre from an UNPROCESSED mic, which runs no AGC.
        val out = room((9.0 * RATE).toInt(), rms = 0.0015, seed = 3)
        var t = 3.0
        var i = 0
        while (t < 8.5) {
            pluck(out, (t * RATE).toInt(), reference.referenceHz(GCEA[i % 4]), 0.06, 1.2)
            t += 1.3
            i++
        }
        val outcome = stream(out)
        println("quiet ukulele: $outcome")
        // The last two plucks are the ones a latching gate silently swallows.
        for (midi in GCEA) {
            assertTrue(
                (outcome.counts["OK:" + spell(midi)] ?: 0) > 0,
                "${spell(midi)} was never detected: $outcome",
            )
        }
        assertTrue(outcome.tooQuiet == 0, "frames were gated as TOO_QUIET: $outcome")
    }

    @Test
    fun singleNotesAreStillFoundAfterASustainedStrum() {
        // The regression. A strum is loud and, being polyphonic, correctly aperiodic - so it is
        // rejected at full signal level. Those rejections must not train the noise floor up to
        // the strum's own level, or every note played afterwards is gated out permanently.
        val out = room((10.0 * RATE).toInt(), rms = 0.0015, seed = 4)
        var t = 0.5
        while (t < 4.5) {
            for (midi in GCEA) {
                pluck(out, ((t + 0.02 * (midi % 5)) * RATE).toInt(), reference.referenceHz(midi), 0.30, 1.5)
            }
            t += 1.0
        }
        t = 6.0
        var i = 0
        while (t < 9.5) {
            pluck(out, (t * RATE).toInt(), reference.referenceHz(GCEA[i % 4]), 0.30, 1.2)
            t += 1.1
            i++
        }
        // Only the single-note section is scored; the strum itself is legitimately unreadable.
        val outcome = stream(out, from = 6.0)
        println("single notes after a strum: $outcome")
        assertTrue(outcome.tooQuiet == 0, "the strum latched the level gate shut: $outcome")
        assertTrue(
            outcome.detected > outcome.frames / 2,
            "only ${outcome.detected}/${outcome.frames} frames read after a strum: $outcome",
        )
    }

    @Test
    fun theFloorStillRejectsAnEmptyRoom() {
        // The gate has to keep doing its job: room noise on its own must never read as a note.
        val outcome = stream(room((6.0 * RATE).toInt(), rms = 0.0015, seed = 9))
        println("empty room: $outcome")
        assertTrue(outcome.detected == 0, "room noise was reported as a note: $outcome")
    }

    private companion object {
        const val RATE = 48_000.0
        const val HOP = 512
        const val UKE_MIN_HZ = 230.0
        const val UKE_MAX_HZ = 1800.0
        val GCEA = listOf(67, 60, 64, 69)
    }
}
