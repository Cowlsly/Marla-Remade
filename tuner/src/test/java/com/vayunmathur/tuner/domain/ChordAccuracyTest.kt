package com.vayunmathur.tuner.domain

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The ship gate for TUNER_SPEC C.4 / H.1: the accuracy tiers are **measured**, not estimated.
 *
 * Synthetic harmonic stacks are an optimistic corpus - no room, no distortion, no sympathetic
 * ringing, exact equal temperament - so these numbers are a ceiling on real-world performance,
 * not a prediction of it. They are still worth having: they catch a regression in the NNLS or
 * the tiering the moment it happens, and the tier-B honesty assertion below is a correctness
 * property that holds for any input at all.
 *
 * Run output prints the per-tier rate so the figures in the spec can be checked against
 * something real rather than restated.
 */
class ChordAccuracyTest {
    private val detector = ChordDetector(Signals.SAMPLE_RATE)
    private val samples = detector.requiredSamples

    /** Feeds one chord through enough frames to clear the detector's temporal stabiliser. */
    private fun read(midis: List<Int>): ChordReading {
        detector.reset()
        val signal = Signals.chord(midis, samples)
        var reading = detector.analyse(signal, ChordTier.CONFIRMED)
        repeat(3) { reading = detector.analyse(signal, ChordTier.CONFIRMED) }
        return reading
    }

    private fun namesIn(reading: ChordReading): List<ChordName> = when (val label = reading.label) {
        is ChordLabel.Chord -> listOf(label.name)
        is ChordLabel.Ambiguous -> listOf(label.primary, label.secondary)
        else -> emptyList()
    }

    private fun matches(reading: ChordReading, root: Int, quality: ChordQuality) =
        namesIn(reading).any { it.root == root && it.quality == quality }

    /**
     * Root-position voicings in the range a guitar or piano actually produces. Each entry is
     * the chord's root pitch class, its quality, and MIDI notes.
     */
    private data class Case(val root: Int, val quality: ChordQuality, val midis: List<Int>)

    /**
     * A detector told the instrument's polyphony never reports more pitch classes than that.
     *
     * A structural property, not an accuracy claim, so it is honest to assert it here: four
     * strings sound four notes, and a fifth reported pitch class is residue by definition. This
     * is the bound that removed every 5pc and 6pc frame from `RealAudioHarness` on Record-3.
     */
    @Test
    fun aCappedDetectorNeverReportsMorePitchClassesThanTheInstrumentCanSound() {
        val capped = ChordDetector(Signals.SAMPLE_RATE, polyphony = 4)
        for (case in tierA + tierB) {
            capped.reset()
            val signal = Signals.chord(case.midis, samples)
            var reading = capped.analyse(signal, ChordTier.CONFIRMED)
            repeat(3) { reading = capped.analyse(signal, ChordTier.CONFIRMED) }
            assertTrue(
                reading.pitchClasses.size <= 4,
                "${case.root}/${case.quality} reported ${reading.pitchClasses.size} pitch classes",
            )
        }
    }

    private val tierA = listOf(
        Case(4, ChordQuality.MAJOR, listOf(40, 47, 52, 56, 59, 64)),
        Case(0, ChordQuality.MAJOR, listOf(48, 52, 55, 60)),
        Case(7, ChordQuality.MAJOR, listOf(43, 47, 50, 55)),
        Case(2, ChordQuality.MAJOR, listOf(50, 54, 57, 62)),
        Case(9, ChordQuality.MINOR, listOf(45, 48, 52, 57)),
        Case(4, ChordQuality.MINOR, listOf(40, 47, 52, 55, 59, 64)),
        Case(2, ChordQuality.MINOR, listOf(50, 53, 57, 62)),
        Case(7, ChordQuality.DOMINANT_SEVENTH, listOf(43, 47, 50, 53)),
        Case(0, ChordQuality.DOMINANT_SEVENTH, listOf(48, 52, 55, 58)),
        Case(2, ChordQuality.DOMINANT_SEVENTH, listOf(50, 54, 57, 60)),
        Case(0, ChordQuality.MAJOR_SEVENTH, listOf(48, 52, 55, 59)),
        Case(5, ChordQuality.MAJOR_SEVENTH, listOf(53, 57, 60, 64)),
        Case(11, ChordQuality.DIMINISHED, listOf(47, 50, 53)),
        Case(2, ChordQuality.DIMINISHED, listOf(50, 53, 56)),
    )

    private val tierB = listOf(
        Case(4, ChordQuality.POWER, listOf(40, 47, 52)),
        Case(0, ChordQuality.SUS2, listOf(48, 50, 55)),
        Case(2, ChordQuality.SUS4, listOf(50, 55, 57)),
        Case(0, ChordQuality.SIXTH, listOf(48, 52, 55, 57)),
        Case(9, ChordQuality.MINOR_SEVENTH, listOf(45, 48, 52, 55)),
        Case(4, ChordQuality.MINOR_SEVENTH, listOf(40, 47, 50, 55, 59, 64)),
        Case(2, ChordQuality.MINOR_SIXTH, listOf(50, 53, 57, 59)),
        Case(11, ChordQuality.HALF_DIMINISHED, listOf(47, 50, 53, 57)),
        Case(0, ChordQuality.AUGMENTED, listOf(48, 52, 56)),
        Case(0, ChordQuality.DIMINISHED_SEVENTH, listOf(48, 51, 54, 57)),
    )

    @Test
    fun tierAChordsAreNamedOutright() {
        val hits = tierA.count { matches(read(it.midis), it.root, it.quality) }
        val rate = hits.toDouble() / tierA.size
        println("MEASURED tier A (named outright): $hits/${tierA.size} = ${percent(rate)}")
        // 43% when first measured on 2026-09-05, 100% after the harmonic dictionary was corrected
        // to a 1/n partial law (see NoteSalience.PARTIALS). The old geometric 0.9^(n-1) made a low
        // note's column able to explain the whole chord above it, so the solver answered with a
        // phantom note an octave below the root whose third partial swallowed the real fifth.
        // On a noiseless corpus with exact equal temperament 100% is the ceiling behaving as it
        // should, not a prediction: the floor is set below it to leave room for honest variation.
        assertTrue(rate >= 0.85, "tier A fell to ${percent(rate)} on synthetic mixes")
    }

    /**
     * Tier B is scored on whether the true chord is *offered*, not on whether it is picked. With
     * no bass supplied it cannot be picked - that is the rule, not a failure - so the honest
     * measurement is whether the correct reading survives into the pair the user is shown.
     */
    @Test
    fun tierBChordsAreOfferedEvenWhenTheyCannotBeNamed() {
        val hits = tierB.count { matches(read(it.midis), it.root, it.quality) }
        val rate = hits.toDouble() / tierB.size
        println("MEASURED tier B (offered as one of the two readings): $hits/${tierB.size} = ${percent(rate)}")
        // 60% on 2026-09-05, 90% after the dictionary correction.
        assertTrue(rate >= 0.80, "tier B fell to ${percent(rate)} on synthetic mixes")
    }

    /** How often the pipeline finds every note that is actually sounding. */
    @Test
    fun noteRecallIsMeasured() {
        var found = 0
        var total = 0
        for (case in tierA + tierB) {
            val expected = case.midis.map { pitchClassOf(it) }.toSet()
            val heard = read(case.midis).pitchClasses
            total += expected.size
            found += expected.count { it in heard }
        }
        val rate = found.toDouble() / total
        println("MEASURED pitch-class recall: $found/$total = ${percent(rate)}")
        // 84% on 2026-09-05, 100% after the dictionary correction. The note set is the honest part
        // of this feature, which is why the UI shows it in every state.
        assertTrue(rate >= 0.95, "note recall fell to ${percent(rate)}")
    }

    /**
     * The hard rule from TUNER_SPEC I.5, asserted over the whole corpus.
     *
     * This one is not a statistic. A tier-B quality appearing as the sole displayed name without
     * a confident bass on its root is a correctness bug, because `Am7` and `C6` are the same
     * four notes and the app would be presenting a coin flip as an answer.
     */
    @Test
    fun noTierBQualityIsEverNamedAloneWithoutAMatchingBass() {
        for (case in tierA + tierB) {
            val reading = read(case.midis)
            val label = reading.label
            if (label !is ChordLabel.Chord) continue
            if (label.name.quality.tier != QualityTier.B) continue
            assertTrue(
                reading.bass == label.name.root,
                "${label.name.quality} named alone with bass ${reading.bass} on ${case.midis}",
            )
        }
    }

    /**
     * The same tier-A corpus, resynthesised with partial laws the dictionary does not assume.
     *
     * The dictionary models partial `n` at `1/n`; the corpus above is geometric `0.75^n`. This
     * varies the generator further still - much faster decay, much slower decay, and a sawtooth -
     * so that a future change cannot quietly tune the dictionary to one synthesis model and call
     * it accuracy. If tier A only survives its own corpus, it has not been measured, it has been
     * fitted.
     */
    @Test
    fun tierASurvivesPartialLawsTheDictionaryDoesNotAssume() {
        for (amplitudes in listOf(
            List(8) { 0.4 * 0.55.pow(it) },
            List(12) { 0.4 * 0.90.pow(it) },
            List(16) { 0.4 / (it + 1) },
        )) {
            val hits = tierA.count { case ->
                val signal = DoubleArray(samples)
                for (midi in case.midis) {
                    val part = Signals.harmonic(PitchReference().referenceHz(midi), samples, amplitudes)
                    for (i in 0 until samples) signal[i] += part[i]
                }
                detector.reset()
                var reading = detector.analyse(signal, ChordTier.CONFIRMED)
                repeat(3) { reading = detector.analyse(signal, ChordTier.CONFIRMED) }
                matches(reading, case.root, case.quality)
            }
            val rate = hits.toDouble() / tierA.size
            println("MEASURED tier A on ${amplitudes.size} partials: $hits/${tierA.size} = ${percent(rate)}")
            assertTrue(rate >= 0.85, "tier A fell to ${percent(rate)} on an unmodelled partial law")
        }
    }

    private fun percent(rate: Double) = "${Math.round(rate * 100)}%"
}
