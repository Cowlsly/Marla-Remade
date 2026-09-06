package com.vayunmathur.tuner.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The deconvolution stage, checked two ways.
 *
 * First against observations built from the dictionary itself, which is the only case where the
 * right answer is known exactly. Then against synthesised audio through the real transform,
 * which is a weaker but more honest check - it is the closest this test suite gets to a
 * measurement of chord accuracy, and it is not one. Real accuracy needs a recorded corpus.
 */
class NnlsTest {
    private val salience = NoteSalience()

    private fun observationFor(midis: List<Int>, weights: List<Double> = midis.map { 1.0 }): DoubleArray {
        val out = DoubleArray(CQT_BINS)
        midis.forEachIndexed { index, midi ->
            val column = salience.column(midi)
            for (bin in 0 until CQT_BINS) out[bin] += weights[index] * column[bin]
        }
        return out
    }

    @Test
    fun aSingleDictionaryColumnIsRecoveredAsOneNote() {
        for (midi in listOf(40, 48, 60, 67, 79, 91)) {
            val notes = pickNotes(salience.solve(observationFor(listOf(midi))))
            assertEquals(listOf(midi), notes.map { it.midi }, "at midi $midi")
        }
    }

    @Test
    fun aThreeNoteChordIsRecoveredExactly() {
        val chords = listOf(
            listOf(48, 52, 55), // C3 E3 G3
            listOf(57, 60, 64), // A3 C4 E4
            listOf(62, 65, 69), // D4 F4 A4
            listOf(40, 47, 52), // E2 B2 E3, an open power chord shape
        )
        for (chord in chords) {
            val notes = pickNotes(salience.solve(observationFor(chord)))
            assertEquals(chord, notes.map { it.midi }, "for $chord")
        }
    }

    @Test
    fun overtonesOfOneNoteAreNotReadAsAChord() {
        // The whole reason this stage exists: a plain chroma of one plucked low C already looks
        // like C7, because C2's partials land on C, C, G, C, E, G, Bb, C.
        val notes = pickNotes(salience.solve(observationFor(listOf(36))))
        assertEquals(listOf(36), notes.map { it.midi })
    }

    @Test
    fun backgroundSubtractionFlattensASlopedSpectrum() {
        // A bright guitar and a dull piano must look alike before the fit, or the loudest region
        // of the spectrum decides the answer rather than the notes.
        val sloped = DoubleArray(CQT_BINS) { 1.0 + it * 0.05 }
        val flattened = salience.subtractBackground(sloped)
        val middle = flattened.copyOfRange(CQT_BINS_PER_OCTAVE, CQT_BINS - CQT_BINS_PER_OCTAVE)
        assertTrue(middle.all { it < 0.05 }, "a smooth ramp should leave almost nothing behind")
    }

    @Test
    fun theBassNoteIsUnknownWhenTwoLowNotesAreEquallyStrong() {
        val ambiguous = listOf(DetectedNote(40, 0.95), DetectedNote(45, 0.90), DetectedNote(64, 0.5))
        assertEquals(null, bassNoteOf(ambiguous))

        val clear = listOf(DetectedNote(40, 1.0), DetectedNote(45, 0.30), DetectedNote(64, 0.5))
        assertEquals(40, bassNoteOf(clear)?.midi)
    }

    @Test
    fun notePickingIsCappedAndKeepsOctaves() {
        val many = DoubleArray(NOTE_COUNT) { if (it % 5 == 0) 1.0 else 0.0 }
        val notes = pickNotes(many)
        assertTrue(notes.size <= 6, "picked ${notes.size} notes")
        assertTrue(notes.all { it.midi >= LOWEST_NOTE_MIDI })
        assertEquals(notes.map { it.midi }.sorted(), notes.map { it.midi })
    }

    @Test
    fun aSynthesisedTriadIsFoundThroughTheRealTransform() {
        // Not an accuracy measurement - one noiseless synthetic signal proves the plumbing is
        // connected, nothing more. TUNER_SPEC H.1 gates the real number on a recorded corpus.
        val detector = ChordDetector(Signals.SAMPLE_RATE)
        val midis = listOf(48, 52, 55)
        val audio = Signals.chord(midis, detector.requiredSamples)
        var reading = detector.analyse(audio, ChordTier.CONFIRMED)
        repeat(2) { reading = detector.analyse(audio, ChordTier.CONFIRMED) }
        val found = reading.pitchClasses
        for (midi in midis) {
            assertTrue(
                pitchClassOf(midi) in found,
                "pitch class of $midi missing from $found (notes ${reading.notes.map { it.midi }})",
            )
        }
    }

    @Test
    fun aConstrainedDictionaryCannotReportANoteTheInstrumentCannotSound() {
        val uke = 60..84
        val constrained = NoteSalience(uke)
        // A C2 that a ukulele could not have produced. The fit must not answer with one.
        val observation = observationFor(listOf(36, 60, 64, 67))
        val notes = pickNotes(constrained.solve(observation))
        assertTrue(notes.isNotEmpty(), "constrained fit found nothing at all")
        assertTrue(notes.all { it.midi in uke }, "out of range: ${notes.map { it.midi }}")
    }

    /**
     * The failure this constraint exists for: a phantom an octave below the root explains the
     * root with its second partial and the fifth with its third, so the fifth disappears while
     * the phantom hides behind the root's own pitch class.
     */
    @Test
    fun anOctaveBelowThePhantomCannotAbsorbTheFifth() {
        val uke = 60..84
        val ukeC = listOf(67, 60, 64, 72)
        // Weight the phantom heavily enough that an unconstrained solver would prefer it.
        val observation = observationFor(listOf(48) + ukeC, listOf(2.0) + ukeC.map { 1.0 })
        val classes = pickNotes(NoteSalience(uke).solve(observation)).map { pitchClassOf(it.midi) }
        assertTrue(pitchClassOf(67) in classes, "fifth absorbed; found $classes")
        assertEquals(setOf(0, 4, 7), classes.toSet(), "expected a clean C major, found $classes")
    }

    @Test
    fun aConstrainedDictionaryLeavesTheInstrumentsOwnNotesAlone() {
        val uke = 60..84
        val constrained = NoteSalience(uke)
        for (midi in listOf(60, 64, 67, 72, 79, 84)) {
            val notes = pickNotes(constrained.solve(observationFor(listOf(midi))))
            assertEquals(listOf(midi), notes.map { it.midi }, "at midi $midi")
        }
    }
}
