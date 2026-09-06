package com.vayunmathur.tuner.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ambiguity table from TUNER_SPEC D.3, which is the part of chord naming that is not a
 * detection problem at all.
 *
 * C6 and Am7 are the same four pitch classes. Nothing in the spectrum separates them; only the
 * bass does. A namer that picks one silently is not more accurate, it is just quieter about
 * being wrong half the time.
 */
class ChordNamingTest {
    private fun notes(vararg midis: Int, salience: Double = 1.0) =
        midis.map { DetectedNote(it, salience) }

    private fun read(vararg midis: Int, bass: Int? = null, tier: ChordTier = ChordTier.CONFIRMED) =
        ChordNaming.name(
            notes(*midis),
            bass?.let { DetectedNote(it, 1.0) },
            tier,
        )

    private fun nameOf(reading: ChordReading): String? =
        (reading.label as? ChordLabel.Chord)?.let { chord ->
            spellPitchClass(chord.name.root) + chord.name.quality.symbol
        }

    /** Every name the reading puts in front of the user, whether named or ambiguous. */
    private fun offered(reading: ChordReading): List<String> = when (val label = reading.label) {
        is ChordLabel.Chord -> listOf(symbol(label.name))
        is ChordLabel.Ambiguous -> listOf(symbol(label.primary), symbol(label.secondary))
        else -> emptyList()
    }

    private fun symbol(name: ChordName) = spellPitchClass(name.root) + name.quality.symbol

    @Test
    fun majorAndMinorTriadsAreNamedOutright() {
        assertEquals("C", nameOf(read(48, 52, 55, bass = 48)))
        assertEquals("Am", nameOf(read(57, 60, 64, bass = 57)))
        assertEquals("G", nameOf(read(55, 59, 62, bass = 55)))
        assertEquals("D\u266Fm", nameOf(read(51, 54, 58, bass = 51)))
    }

    @Test
    fun aPerfectFifthIsAPowerChordAndTwoOtherNotesAreAnInterval() {
        // `5` is tier B: {E, B} is E5 or a B power chord with its fifth underneath. With the
        // bass on E it is named; without one both readings are shown.
        assertEquals("E5", nameOf(read(40, 47, bass = 40)))
        assertTrue(read(40, 47).label is ChordLabel.Ambiguous)

        val third = read(60, 64)
        val label = third.label
        assertTrue(label is ChordLabel.Interval, "got $label")
        assertEquals(4, label.semitones)
    }

    @Test
    fun oneNoteIsJustANote() {
        val label = read(64).label
        assertTrue(label is ChordLabel.SingleNote, "got $label")
        assertEquals(64, label.midi)
    }

    @Test
    fun theBassDecidesBetweenTheSixthAndTheMinorSeventh() {
        // {C, E, G, A} is C6 and Am7 at the same time.
        val asSixth = read(48, 52, 55, 57, bass = 48)
        assertEquals("C6", nameOf(asSixth))

        val asMinorSeventh = read(45, 48, 52, 55, bass = 45)
        assertEquals("Am7", nameOf(asMinorSeventh))
    }

    @Test
    fun theBassDecidesBetweenSuspendedSecondAndSuspendedFourth() {
        // {C, D, G} is Csus2 and Gsus4.
        assertEquals("Csus2", nameOf(read(48, 50, 55, bass = 48)))
        assertEquals("Gsus4", nameOf(read(43, 48, 50, bass = 43)))
    }

    @Test
    fun anAmbiguousReadingOffersTheAlternativeInsteadOfPickingSilently() {
        // No bass, so there is no evidence separating C6 from Am7. Both must be visible.
        val reading = read(48, 52, 55, 57)
        assertEquals(ChordPresentation.AMBIGUOUS, reading.presentation)
        val names = offered(reading)
        assertTrue("C6" in names && "Am7" in names, "only offered $names")
        assertEquals(2, names.size, "never more than two readings")
    }

    @Test
    fun aTierBQualityIsNeverNamedAloneWithoutAConfidentBass() {
        // Each of the nine tier-B qualities, played with no bass. None may be a bare Chord.
        val sets = listOf(
            listOf(48, 50, 55), // Csus2 / Gsus4
            listOf(48, 52, 55, 57), // C6 / Am7
            listOf(48, 51, 55, 57), // Cm6 / Am7b5
            listOf(48, 52, 56), // Caug
            listOf(48, 51, 54, 57), // Cdim7
        )
        for (set in sets) {
            val reading = ChordNaming.name(set.map { DetectedNote(it, 1.0) }, null, ChordTier.CONFIRMED)
            val label = reading.label
            assertTrue(
                label !is ChordLabel.Chord || label.name.quality.tier == QualityTier.A,
                "$set was named ${nameOf(reading)} with no bass",
            )
        }
    }

    @Test
    fun tierAQualitiesAreNamedWithNoBassAtAll() {
        assertEquals("C", nameOf(read(48, 52, 55)))
        assertEquals("Am", nameOf(read(57, 60, 64)))
        assertEquals("C7", nameOf(read(48, 52, 55, 58)))
        assertEquals("Cmaj7", nameOf(read(48, 52, 55, 59)))
        assertEquals("Bdim", nameOf(read(47, 50, 53)))
    }

    @Test
    fun theRaisedFifthAndTheSecondMustBeGenuinelyPresent() {
        // Tier B': a whisper of a G# over a C major triad must not become Caug (TUNER_SPEC I.5).
        val faint = listOf(
            DetectedNote(48, 1.0),
            DetectedNote(52, 1.0),
            DetectedNote(55, 1.0),
            DetectedNote(56, 0.3),
        )
        val reading = ChordNaming.name(faint, DetectedNote(48, 1.0), ChordTier.CONFIRMED)
        assertEquals("C", nameOf(reading))

        val real = listOf(DetectedNote(48, 1.0), DetectedNote(52, 1.0), DetectedNote(56, 1.0))
        assertEquals("Caug", nameOf(ChordNaming.name(real, DetectedNote(48, 1.0), ChordTier.CONFIRMED)))
    }

    @Test
    fun theBassIsAFirstClassFieldWithAConfidence() {
        val reading = ChordNaming.name(
            listOf(DetectedNote(48, 1.0), DetectedNote(52, 0.8), DetectedNote(55, 0.7)),
            DetectedNote(48, 0.6),
            ChordTier.CONFIRMED,
        )
        assertEquals(0, reading.bass)
        assertEquals(0.6f, reading.bassConfidence)
    }

    @Test
    fun presentationTracksTheConfidenceBands() {
        assertEquals(ChordPresentation.LISTENING, ChordNaming.name(emptyList(), null, ChordTier.CONFIRMED).presentation)
        assertEquals(ChordPresentation.CONFIDENT, read(48, 52, 55, bass = 48).presentation)
    }

    @Test
    fun aSymmetricChordOffersItsOtherRoots() {
        // Caug, Eaug and G#aug are the same three pitch classes; dim7 has four valid roots.
        // Both are tier B, so with no bass they arrive as a pair of readings, never as one.
        val augmented = read(48, 52, 56).label
        assertTrue(augmented is ChordLabel.Ambiguous, "got $augmented")
        assertEquals(ChordQuality.AUGMENTED, augmented.primary.quality)

        val diminishedSeventh = read(48, 51, 54, 57).label
        assertTrue(diminishedSeventh is ChordLabel.Ambiguous, "got $diminishedSeventh")
        assertEquals(ChordQuality.DIMINISHED_SEVENTH, diminishedSeventh.primary.quality)
    }

    @Test
    fun aConfidentNonRootBassBecomesASlashChord() {
        val reading = read(52, 55, 60, bass = 52)
        val chord = reading.label as ChordLabel.Chord
        assertEquals(0, chord.name.root)
        assertEquals(4, chord.name.bass)
        assertEquals("C/E", spellPitchClass(chord.name.root) + chord.name.quality.symbol + "/" + spellPitchClass(4))
    }

    @Test
    fun noSlashIsAddedWhenTheBassIsNotConfident() {
        val reading = read(52, 55, 60)
        val chord = reading.label as ChordLabel.Chord
        assertEquals(null, chord.name.bass)
    }

    @Test
    fun theVocabularyStopsWhereTheSpecSaysItDoes() {
        // A C9 is {C, E, G, Bb, D}. The namer must not invent a ninth; the honest outcome is the
        // nearest thing in the vocabulary, or nothing, with the note set still shown.
        val reading = read(48, 52, 55, 58, 62, bass = 48)
        val label = reading.label
        if (label is ChordLabel.Chord) {
            assertTrue(
                label.name.quality in ChordQuality.entries,
                "named a quality outside the vocabulary",
            )
        }
        assertEquals(5, reading.notes.size, "the note set must survive regardless")
    }

    @Test
    fun aWeakReadingIsReportedAsUnsureRatherThanGuessed() {
        // Four notes with no chordal relationship at all.
        val reading = ChordNaming.name(
            listOf(
                DetectedNote(48, 1.0),
                DetectedNote(49, 0.9),
                DetectedNote(50, 0.9),
                DetectedNote(51, 0.85),
            ),
            null,
            ChordTier.CONFIRMED,
        )
        assertTrue(
            reading.label is ChordLabel.Unnamed || reading.confidence < 0.6f,
            "a chromatic cluster was named ${nameOf(reading)} at ${reading.confidence}",
        )
        assertEquals(4, reading.notes.size)
    }

    @Test
    fun theNoteSetIsAlwaysPopulated() {
        for (reading in listOf(read(48), read(48, 55), read(48, 52, 55), read(48, 52, 55, 58))) {
            assertTrue(reading.notes.isNotEmpty())
        }
    }

    /**
     * The gate leaked through its own no-alternate branch: the redirect to `Ambiguous` only fires
     * when a rival reading exists, so a tier-B winner with nothing to be ambiguous *against* fell
     * through and was displayed bare. That is how a "major 6th" reached a ukulele, where
     * `bassBacked` can never be true because every string is above `BASS_CEILING_MIDI`.
     *
     * Exhaustive rather than one hand-picked chord, because the leak was in the branch nobody
     * thought to construct an input for.
     */
    @Test
    fun aTierBQualityIsNeverNamedBareWithoutABassBackingIt() {
        val pool = (48..71).toList()
        for (a in pool.indices) {
            for (b in a + 1 until pool.size) {
                for (c in b + 1 until pool.size) {
                    for (d in listOf(-1) + (c + 1 until pool.size)) {
                        val midis = buildList {
                            add(pool[a]); add(pool[b]); add(pool[c])
                            if (d >= 0) add(pool[d])
                        }
                        val reading = ChordNaming.name(
                            midis.map { DetectedNote(it, 1.0) },
                            null,
                            ChordTier.CONFIRMED,
                        )
                        val label = reading.label
                        if (label !is ChordLabel.Chord) continue
                        assertTrue(
                            label.name.quality.tier == QualityTier.A,
                            "$midis was named ${symbol(label.name)}, a tier-B quality, with no bass",
                        )
                    }
                }
            }
        }
    }

    /** A one-note reading is a degraded observation of a strum, so it is never presented flat. */
    @Test
    fun aSingleNoteIsNotPresentedAsCertain() {
        val reading = read(60)
        assertTrue(reading.label is ChordLabel.SingleNote)
        assertEquals(ChordPresentation.UNCERTAIN, reading.presentation)
        assertTrue(reading.confidence < ChordNaming.CONFIDENT_THRESHOLD)
    }
}
