package com.vayunmathur.tuner.domain

import com.vayunmathur.tuner.data.DEFAULT_PARTIALS

/** Which note extractor the Chord tab runs. See [ChordDetector]. */
enum class ChordExtractor {
    /**
     * Greedy harmonic sieve over a closed candidate set. The default, on measured evidence -
     * see [HarmonicSieve] for the numbers.
     */
    SIEVE,

    /**
     * NNLS deconvolution against a dictionary of harmonic combs.
     *
     * Kept because it is the principled method and the comparison has to stay re-runnable, not
     * because it is a fallback: it lost on real audio by 2.2x on frames-named-correctly and
     * nothing selects it at runtime.
     */
    NNLS,
}

/**
 * The Chord tab's pipeline: CQT, a tonality gate, note extraction, temporal stabilisation, then
 * naming (TUNER_SPEC C.2).
 *
 * Extraction is [ChordExtractor.SIEVE] by default. NNLS was the original design and is still
 * reachable through [extractor], but it produced 1.89 notes on a tonal frame of Record-3 against
 * the sieve's 3.32 - and a triad needs three, so it was rarely naming anything at all.
 *
 * The CQT is still computed on every frame even under the sieve, because two things other than
 * extraction need it: the [Tonality] gate reads spectral flatness off the raw frame, and
 * [TuningOffset] reads the instrument's mistuning off the same bins. Both are one pass over 180
 * bins and neither needs a second transform.
 *
 * The two-tier design is what keeps the tab responsive. The 786 ms kernel applies only to the
 * bottom octave, so a [ChordTier.PROVISIONAL] pass over the top three octaves can name a chord
 * quality about 150 ms after the strum - with no bass note, and therefore no slash chords - and
 * the [ChordTier.CONFIRMED] pass firms it up once the full window is valid.
 *
 * Not thread-safe: it carries frame history. Call it from one analysis coroutine.
 */
class ChordDetector(
    sampleRate: Double,
    soundingRange: IntRange = NoteSalience.FULL_RANGE,
    partials: List<Double> = DEFAULT_PARTIALS,
    private val extractor: ChordExtractor = ChordExtractor.SIEVE,
    /**
     * The tonality gate's flatness threshold. Exposed so `RealAudioHarnessTest` can sweep it
     * against real audio rather than against the synthetic corpus, which cannot say anything
     * useful about where it belongs.
     */
    private val maxFlatness: Double = Tonality.MAX_FLATNESS,
    polyphony: Int = HarmonicSieve.MAX_NOTES,
    relativeFloor: Double = HarmonicSieve.RELATIVE_FLOOR,
) {
    private val constantQ = ConstantQ(sampleRate)
    private val sieve = HarmonicSieve(sampleRate, soundingRange, partials, polyphony, relativeFloor)
    private val tuning = TuningOffset()
    private var confirmed = NoteSalience(soundingRange, partials)
    private var provisional = NoteSalience(soundingRange, partials, PROVISIONAL_FIRST_BIN)
    private var range = soundingRange
    private var profile = partials
    private var maxNotes = polyphony
    private val history = ArrayDeque<Set<Int>>()

    /**
     * Narrows the dictionary to the notes an instrument can sound (see `Instrument.soundingRange`)
     * and rebuilds its atoms from that instrument's measured partials (`Instrument.partials`).
     *
     * Rebuilding is a fraction of a millisecond and only happens when the user picks a different
     * instrument, so this is a setter rather than a new detector: the CQT kernel bank and
     * [requiredSamples] do not depend on either, and the caller's capture buffer is sized from
     * the latter.
     */
    fun setInstrument(
        soundingRange: IntRange,
        partials: List<Double>,
        polyphony: Int = HarmonicSieve.MAX_NOTES,
    ) {
        if (soundingRange == range && partials == profile && polyphony == maxNotes) return
        range = soundingRange
        profile = partials
        maxNotes = polyphony
        sieve.setInstrument(soundingRange, partials, polyphony)
        confirmed = NoteSalience(soundingRange, partials)
        provisional = NoteSalience(soundingRange, partials, PROVISIONAL_FIRST_BIN)
        reset()
    }

    /** Samples of 48 kHz audio one hop consumes. */
    val requiredSamples: Int get() = constantQ.requiredSamples

    /** The mistuning the sieve is currently compensating for, in cents. See [TuningOffset]. */
    val tuningCents: Double get() = tuning.cents

    /** Drops frame history and the tuning estimate. Call when capture restarts. */
    fun reset() {
        history.clear()
        tuning.reset()
    }

    /**
     * Runs one hop and names what it found.
     *
     * [bassInformsRoot] is false for a re-entrant instrument, where the lowest sounding pitch is
     * routinely not the root (see `Instrument.reentrant`). It suppresses the bass rather than
     * letting a wrong one through: on a ukulele the lowest note of an F chord is C, so a bass
     * taken from pitch order would hard-gate the tier-B vocabulary onto the wrong root and
     * produce confidently wrong slash chords.
     *
     * Note that `bassNoteOf` is separately vacuous above F3 today, so on a ukulele - whose
     * lowest possible note is C4 - this changes nothing yet. It is here so that raising that
     * ceiling, which is the obvious fix to the guitar-derived constant, cannot silently break
     * the re-entrant case.
     */
    fun analyse(
        samples: DoubleArray,
        tier: ChordTier,
        bassInformsRoot: Boolean = true,
    ): ChordReading {
        val magnitudes = constantQ.magnitudes(samples)
        tuning.observe(magnitudes)
        val firstBin = if (tier == ChordTier.PROVISIONAL) PROVISIONAL_FIRST_BIN else 0
        val picked = when (extractor) {
            ChordExtractor.SIEVE -> sieveNotes(magnitudes, samples, firstBin)
            ChordExtractor.NNLS -> nnlsNotes(magnitudes, tier, firstBin)
        } ?: run {
            history.clear()
            return silence(tier)
        }

        val stable = stabilise(picked)
        if (stable.isEmpty()) return silence(tier)

        val bass = if (tier == ChordTier.CONFIRMED && bassInformsRoot) bassNoteOf(stable) else null
        return ChordNaming.name(stable, bass, tier)
    }

    /**
     * Sieve extraction, gated on flatness and on the sieve's own absolute peak.
     *
     * Null means the frame was not worth extracting from, which is a different answer from "no
     * notes found" - the caller clears the history for it. The gate cannot reuse
     * [Tonality.MIN_PEAK_SALIENCE], whose units are log-magnitude above a local mean and belong
     * to `subtractBackground`; [HarmonicSieve.SILENCE_FLOOR] is the same idea in the sieve's own
     * signal-amplitude units.
     */
    private fun sieveNotes(
        magnitudes: DoubleArray,
        samples: DoubleArray,
        firstBin: Int,
    ): List<DetectedNote>? {
        if (spectralFlatness(magnitudes.copyOfRange(firstBin, CQT_BINS)) > maxFlatness) {
            return null
        }
        val notes = sieve.notes(samples, tuning.cents)
        if (sieve.peakAmplitude < HarmonicSieve.SILENCE_FLOOR) return null
        return notes
    }

    /**
     * NNLS extraction. Retained so [ChordExtractor.NNLS] stays runnable and the comparison in
     * `RealAudioHarnessTest` keeps measuring the code that actually shipped, rather than a copy
     * of it that can drift.
     */
    private fun nnlsNotes(
        magnitudes: DoubleArray,
        tier: ChordTier,
        firstBin: Int,
    ): List<DetectedNote>? {
        val salience = if (tier == ChordTier.PROVISIONAL) provisional else confirmed
        val observation = salience.subtractBackground(magnitudes)
        if (tier == ChordTier.PROVISIONAL) {
            // The bottom two octaves have not accumulated a valid window yet; leaving them in
            // would let the transient at the start of the kernel masquerade as a bass note. The
            // provisional dictionary is masked to the same bins, so the fit stays consistent -
            // clipping only the observation deletes C4, whose kernel straddles the boundary.
            for (bin in 0 until PROVISIONAL_FIRST_BIN) observation[bin] = 0.0
        }
        if (observation.max() <= 0.0) return null

        // NNLS fits whatever it is handed, so this is the only place that asks whether the frame
        // was worth fitting. Without it the namer receives a pitch-class set built from room
        // noise and names it - the Note tab never does, because YIN rejects aperiodic input
        // using a number it had to compute anyway.
        val fit = salience.fit(observation)
        val tonality = tonalityOf(magnitudes.copyOfRange(firstBin, CQT_BINS), fit)
        if (tonality.flatness > maxFlatness) return null
        if (tonality.peakSalience < Tonality.MIN_PEAK_SALIENCE) return null
        return pickNotes(fit.salience)
    }

    /**
     * Requires a note in two of the last three frames before it enters the reported set. Costs
     * one hop of latency and removes most single-frame flicker.
     */
    private fun stabilise(picked: List<DetectedNote>): List<DetectedNote> {
        history.addLast(picked.map { it.midi }.toSet())
        while (history.size > HISTORY_FRAMES) history.removeFirst()
        if (history.size < HISTORY_FRAMES) return picked
        return picked.filter { note -> history.count { note.midi in it } >= REQUIRED_FRAMES }
    }

    private fun silence(tier: ChordTier) = ChordReading(
        notes = emptyList(),
        bass = null,
        bassConfidence = 0f,
        label = ChordLabel.Silent,
        alternates = emptyList(),
        confidence = 0f,
        tier = tier,
        presentation = ChordPresentation.LISTENING,
    )

    private companion object {
        /** Bin 72 is C4: the bottom of the top three octaves. */
        const val PROVISIONAL_FIRST_BIN = 2 * CQT_BINS_PER_OCTAVE
        const val HISTORY_FRAMES = 3
        const val REQUIRED_FRAMES = 2
    }
}
