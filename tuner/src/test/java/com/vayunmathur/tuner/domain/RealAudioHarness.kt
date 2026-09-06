package com.vayunmathur.tuner.domain

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Offline measurement against a real recording, kept out of the graded suite on purpose.
 *
 * The synthetic corpus cannot answer the questions this answers. `Signals.chord` builds a stack
 * of partials and the NNLS dictionary is a stack of partials, so `ChordAccuracyTest` is largely
 * measuring whether the solver can invert a spectrum it effectively constructed. Every synthetic
 * number has been excellent while every reported real one has been poor, which is what that
 * circularity looks like from outside. So this file drives the same pipeline from a decoded
 * recording and reports frames-named, not notes-per-frame alone.
 *
 * The recording is not in the tree - it is 2.4 MB of PCM and this is a measurement tool, not a
 * regression gate. [RealAudioHarness.pcmOrNull] returns null when it is absent and every caller
 * skips, so a checkout without it still builds and tests green.
 */
object RealAudioHarness {
    /** Mono 32-bit float, little endian, decoded to [RATE]. See `docs` in the report header. */
    const val PCM_PATH: String = "src/test/resources/Record-3.f32le.pcm"

    /**
     * The rate the recording is decoded to, which must be the rate the device captures at -
     * otherwise every measurement here is of a pipeline the phone never runs.
     *
     * Asserted against `AudioCapture.SAMPLE_RATE` in [RealAudioHarnessTest]. It is not read from
     * that class directly because `AudioCapture` is an Android platform type; the constant is a
     * compile-time `const` so the check costs nothing at runtime.
     */
    const val RATE: Double = 48_000.0

    /** 8 note hops of 512 samples, matching `TunerViewModel.CHORD_EVERY` (~85 ms). */
    const val HOP: Int = 8 * 512

    fun pcmOrNull(): DoubleArray? {
        val file = File(PCM_PATH)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(bytes.size / 4) { buffer.getFloat(it * 4).toDouble() }
    }

    /**
     * The recording's tuning offset in cents, negative for flat, modulo one semitone.
     *
     * A ukulele that is badly flat puts every fundamental between CQT bins, and both the
     * dictionary and the sieve score at exact semitone centres, so leaving this uncompensated
     * penalises both methods for something that is not their fault.
     *
     * Method: parabolic interpolation of every strong local maximum in the CQT, then the circular
     * median deviation from the nearest semitone. Circular because the quantity wraps: an offset
     * of -90 cents and one of +10 produce an identical bin alignment, and this function cannot
     * tell them apart. That is not a defect to fix here - it is information the audio does not
     * contain. Which whole semitone the recording sits in is resolved by [SEMITONE_CANDIDATES].
     */
    fun tuningOffsetCents(pcm: DoubleArray): Double {
        val constantQ = ConstantQ(RATE)
        var sumSin = 0.0
        var sumCos = 0.0
        var count = 0
        for (frame in hops(pcm, constantQ.requiredSamples, HOP)) {
            val magnitudes = constantQ.magnitudes(frame)
            val ceiling = magnitudes.max()
            if (ceiling <= 0.0) continue
            for (bin in 1 until CQT_BINS - 1) {
                val here = magnitudes[bin]
                if (here < PEAK_FRACTION * ceiling) continue
                if (here <= magnitudes[bin - 1] || here <= magnitudes[bin + 1]) continue
                val a = ln(magnitudes[bin - 1] + 1e-12)
                val b = ln(here + 1e-12)
                val c = ln(magnitudes[bin + 1] + 1e-12)
                val denominator = a - 2.0 * b + c
                if (abs(denominator) < 1e-12) continue
                val shift = 0.5 * (a - c) / denominator
                if (abs(shift) > 0.5) continue
                val semitones = (bin + shift) / 3.0
                val deviation = (semitones - semitones.roundToInt()) * 100.0
                val angle = 2.0 * Math.PI * deviation / 100.0
                sumSin += kotlin.math.sin(angle)
                sumCos += kotlin.math.cos(angle)
                count++
            }
        }
        if (count == 0) return 0.0
        return kotlin.math.atan2(sumSin, sumCos) * 100.0 / (2.0 * Math.PI)
    }

    /**
     * Whole-semitone shifts to try on top of [tuningOffsetCents], flattest first.
     *
     * Only the fraction of a semitone is measurable from the spectrum. Whether the player was 10
     * cents sharp or 90 flat is a question about intent, and the answer is chosen by which shift
     * actually names chords - reported for all of them so the choice is visible rather than
     * assumed.
     */
    val SEMITONE_CANDIDATES: List<Int> = listOf(0, -1, -2)

    /**
     * A [ConstantQ] whose bins sit where the recording's notes actually are.
     *
     * Telling the transform the rate is higher than it is scales every kernel's normalised centre
     * frequency down by the same factor, which slides the whole bin grid flat with the recording.
     * Retuning the grid is preferable to resampling the audio: it leaves the samples untouched.
     */
    fun compensated(cents: Double): ConstantQ = ConstantQ(RATE * 2.0.pow(-cents / 1200.0))

    fun hops(signal: DoubleArray, window: Int, hop: Int): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var end = window
        while (end <= signal.size) {
            out += signal.copyOfRange(end - window, end)
            end += hop
        }
        return out
    }

    /**
     * RMS level of the last [window] samples in dBFS - the audio the sieve's FFT actually sees.
     *
     * Frame level is the quantity the whole "adaptive extraction" argument rests on: if quiet
     * frames are the ones yielding a single pitch class and loud frames the ones yielding five,
     * a level-dependent criterion has something to work with. If they overlap, it does not.
     */
    fun levelDb(frame: DoubleArray, window: Int): Double {
        val start = (frame.size - window).coerceAtLeast(0)
        var sum = 0.0
        for (i in start until frame.size) sum += frame[i] * frame[i]
        return 20.0 * log10(sqrt(sum / (frame.size - start)) + 1e-12)
    }

    /** C, Am and F - what is actually played on the recording. */
    val GROUND_TRUTH: Set<Pair<Int, ChordQuality>> = setOf(
        0 to ChordQuality.MAJOR,
        9 to ChordQuality.MINOR,
        5 to ChordQuality.MAJOR,
    )

    private const val PEAK_FRACTION = 0.25
}

/**
 * What one method scored over the whole recording.
 *
 * [framesNamed] is the headline. Notes per frame is the number that has been quoted so far and it
 * is not the same question: a method can average two notes a frame and never name anything,
 * because the two are not the same two from frame to frame.
 */
class Score(val label: String) {
    var frames: Int = 0
    var tonal: Int = 0
    var notes: Int = 0
    var named: Int = 0
    var correct: Int = 0
    var offered: Int = 0
    val names: MutableMap<String, Int> = LinkedHashMap()

    /**
     * Why each tonal frame ended where it did, and how many *distinct pitch classes* it carried.
     *
     * Both are here because "notes per tonal frame" cannot answer the question they answer.
     * `ChordNaming` scores pitch classes, not notes, and it declines outright below three of
     * them - so a frame averaging 3.4 notes may still be a two-pitch-class frame that never
     * reached the scorer. Distinguishing "extraction did not find a third pitch class" from
     * "the scorer saw three and rejected them" decides which end of the pipeline to work on,
     * and the two are indistinguishable from the note count alone.
     */
    val outcomes: MutableMap<String, Int> = LinkedHashMap()
    val pitchClassHistogram: MutableMap<Int, Int> = LinkedHashMap()

    /**
     * Frame levels in dBFS grouped by how many pitch classes that frame produced.
     *
     * Tests the standing theory that 1pc frames are quiet decay tails and 5-6pc frames are loud
     * ones accumulating spurious classes. If the medians here do not separate, the theory is
     * wrong and no level-dependent criterion can fix either end.
     */
    val levelsByPitchClass: MutableMap<Int, MutableList<Double>> = LinkedHashMap()

    fun record(reading: ChordReading, levelDb: Double = Double.NaN) {
        frames++
        if (reading.label is ChordLabel.Silent) return
        tonal++
        notes += reading.notes.size
        val distinct = reading.pitchClasses.size
        pitchClassHistogram[distinct] = (pitchClassHistogram[distinct] ?: 0) + 1
        if (!levelDb.isNaN()) {
            levelsByPitchClass.getOrPut(distinct) { ArrayList() } += levelDb
        }
        val outcome = when (reading.label) {
            is ChordLabel.SingleNote -> "SingleNote (1 pitch class)"
            is ChordLabel.Interval -> "Interval (2 pitch classes)"
            is ChordLabel.Unnamed -> "Unnamed (scored, rejected)"
            is ChordLabel.Ambiguous -> "Ambiguous (offered, not named)"
            is ChordLabel.Chord -> "Chord (named)"
            is ChordLabel.Silent -> "Silent"
        }
        outcomes[outcome] = (outcomes[outcome] ?: 0) + 1
        when (val label = reading.label) {
            is ChordLabel.Chord -> {
                named++
                offered++
                val name = label.name
                names[name.toString()] = (names[name.toString()] ?: 0) + 1
                if (name.root to name.quality in RealAudioHarness.GROUND_TRUTH) correct++
            }

            is ChordLabel.Ambiguous -> offered++
            else -> Unit
        }
    }

    private fun percent(part: Int) = if (frames == 0) 0.0 else 100.0 * part / frames

    fun report(): String = buildString {
        appendLine("--- $label")
        appendLine("  frames                 $frames")
        appendLine("  frames tonal           $tonal (${"%.1f".format(percent(tonal))}%)")
        appendLine("  FRAMES NAMED           $named (${"%.1f".format(percent(named))}%)")
        appendLine("  frames named correctly $correct (${"%.1f".format(percent(correct))}%)")
        appendLine("  frames offered a name  $offered (${"%.1f".format(percent(offered))}%)")
        val perTonal = if (tonal == 0) 0.0 else notes.toDouble() / tonal
        val perFrame = if (frames == 0) 0.0 else notes.toDouble() / frames
        appendLine("  notes / tonal frame    ${"%.2f".format(perTonal)}")
        appendLine("  notes / frame          ${"%.2f".format(perFrame)}")
        val top = names.entries.sortedByDescending { it.value }.take(8)
        appendLine("  names                  ${top.joinToString { "${it.key}x${it.value}" }}")
        appendLine("  distinct pitch classes ${pitchClassHistogram.toSortedMap().entries.joinToString { "${it.key}pc x${it.value}" }}")
        for ((outcome, count) in outcomes.entries.sortedByDescending { it.value }) {
            appendLine("    %-32s %d".format(outcome, count))
        }
        if (levelsByPitchClass.isNotEmpty()) {
            appendLine("  frame level (dBFS RMS) by pitch-class count")
            appendLine("      pc  n      min   median      max")
            for ((count, levels) in levelsByPitchClass.toSortedMap()) {
                val sorted = levels.sorted()
                appendLine(
                    "    %4d %2d %8.1f %8.1f %8.1f".format(
                        count,
                        sorted.size,
                        sorted.first(),
                        sorted[sorted.size / 2],
                        sorted.last(),
                    ),
                )
            }
        }
    }
}
