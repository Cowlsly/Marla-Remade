package com.vayunmathur.tuner.domain

import com.vayunmathur.tuner.data.InstrumentCatalog
import com.vayunmathur.tuner.data.InstrumentKind
import com.vayunmathur.tuner.data.MUTED_FRET
import com.vayunmathur.tuner.data.StringSpec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shape generator, and the one correctness trap in it.
 *
 * Pitch order is not string order. A re-entrant ukulele's first string is its second highest,
 * so anything deriving "the bass note" from index zero is wrong in a way that only shows up on
 * exactly the instrument we ship.
 */
class VoicingTest {
    private val catalog = InstrumentCatalog.parse(
        File("src/main/assets/instruments.json").readText(),
    )
    private val guitar = catalog.byId("guitar-standard")
    private val ukulele = catalog.byId("ukulele-gcea")

    private fun chord(root: Int, quality: ChordQuality) = ChordName(root, quality)

    @Test
    fun theBundledCatalogueIsInternallyConsistent() {
        assertEquals(3, catalog.instruments.size)
        for (instrument in catalog.instruments) {
            assertTrue(instrument.analysis.minHz < instrument.analysis.maxHz, instrument.id)
            when (instrument.kind) {
                InstrumentKind.FRETTED -> {
                    assertTrue(instrument.strings.isNotEmpty(), instrument.id)
                    assertTrue(instrument.fretCount > 0, instrument.id)
                    assertTrue(instrument.strings.all { it.label.isNotBlank() }, instrument.id)
                }

                InstrumentKind.KEYBOARD -> {
                    val keyboard = assertNotNull(instrument.keyboard, instrument.id)
                    assertTrue(keyboard.lowestMidi < keyboard.highestMidi)
                }

                InstrumentKind.UNFRETTED -> assertTrue(instrument.strings.isNotEmpty())
            }
        }
        assertEquals("E A D G B E", guitar.tuningLabel)
        assertEquals("G C E A", ukulele.tuningLabel)
    }

    @Test
    fun theTextbookOpenGuitarShapesRankFirst() {
        val expected = mapOf(
            chord(4, ChordQuality.MAJOR) to listOf(0, 2, 2, 1, 0, 0),
            chord(4, ChordQuality.MINOR) to listOf(0, 2, 2, 0, 0, 0),
            chord(9, ChordQuality.MAJOR) to listOf(-1, 0, 2, 2, 2, 0),
            chord(9, ChordQuality.MINOR) to listOf(-1, 0, 2, 2, 1, 0),
            chord(2, ChordQuality.MAJOR) to listOf(-1, -1, 0, 2, 3, 2),
            chord(7, ChordQuality.MAJOR) to listOf(3, 2, 0, 0, 0, 3),
            chord(0, ChordQuality.MAJOR) to listOf(-1, 3, 2, 0, 1, 0),
            chord(5, ChordQuality.MAJOR) to listOf(1, 3, 3, 2, 1, 1),
        )
        for ((name, shape) in expected) {
            val voicings = VoicingGenerator.generate(guitar, name)
            assertTrue(voicings.isNotEmpty(), "no shapes for ${name.root} ${name.quality}")
            assertEquals(
                shape,
                voicings.single { it.isPrimary }.frets,
                "wrong primary for ${name.quality}",
            )
        }
    }

    @Test
    fun theTextbookOpenUkuleleShapesRankFirst() {
        val expected = mapOf(
            chord(0, ChordQuality.MAJOR) to listOf(0, 0, 0, 3),
            chord(9, ChordQuality.MINOR) to listOf(2, 0, 0, 0),
            chord(5, ChordQuality.MAJOR) to listOf(2, 0, 1, 0),
            chord(7, ChordQuality.MAJOR) to listOf(0, 2, 3, 2),
        )
        for ((name, shape) in expected) {
            val voicings = VoicingGenerator.generate(ukulele, name)
            assertTrue(voicings.isNotEmpty(), "no shapes for ${name.root} ${name.quality}")
            assertEquals(
                shape,
                voicings.single { it.isPrimary }.frets,
                "wrong primary for ${name.quality}",
            )
        }
    }

    @Test
    fun everyGeneratedShapeKeepsTheTonesThatCarryTheChord() {
        // The 5th is the one tone the note-dropping policy is allowed to lose - the standard
        // open C7, x32310, is exactly that shape and is what a player expects. The 3rd carries
        // major-versus-minor and the 7th is the reason the chord is a 7th, so those must stay.
        for (root in 0..11) {
            for (quality in listOf(ChordQuality.MAJOR, ChordQuality.MINOR, ChordQuality.DOMINANT_SEVENTH)) {
                val name = chord(root, quality)
                val essential = name.pitchClasses - pitchClassOf(root + 7)
                for (voicing in VoicingGenerator.generate(guitar, name)) {
                    val classes = voicing.soundingMidi.map { pitchClassOf(it) }.toSet()
                    assertTrue(
                        classes.containsAll(essential),
                        "$root $quality shape ${voicing.frets} lost the 3rd or the 7th",
                    )
                }
            }
        }
    }

    @Test
    fun everyGeneratedShapeIsPhysicallyPlayable() {
        for (root in 0..11) {
            for (voicing in VoicingGenerator.generate(guitar, chord(root, ChordQuality.MINOR_SEVENTH))) {
                val fretted = voicing.frets.filter { it > 0 }
                if (fretted.isNotEmpty()) {
                    assertTrue(fretted.max() - fretted.min() < 4, "span too wide: ${voicing.frets}")
                    assertTrue(fretted.max() <= 12, "too high: ${voicing.frets}")
                }
                assertTrue(voicing.fingerCount <= 4, "too many fingers: ${voicing.frets}")
            }
        }
    }

    @Test
    fun alternatesAreOfferedAndRanked() {
        val voicings = VoicingGenerator.generate(guitar, chord(0, ChordQuality.MAJOR))
        assertTrue(voicings.size > 1, "only one shape offered")
        assertTrue(voicings.size <= 4, "offered ${voicings.size} shapes")

        // Ordered by neck position so swiping walks up the neck (TUNER_SPEC I.6).
        assertEquals(voicings.map { it.position }.sorted(), voicings.map { it.position })
        // The override is pinned first; the generated remainder must still be in cost order.
        val generated = voicings.drop(1)
        assertEquals(generated.sortedBy { it.cost }, generated)
    }

    @Test
    fun aReentrantUkuleleFindsItsBassByPitchNotByStringIndex() {
        // GCEA re-entrant: string 0 is G4 (MIDI 67) but the lowest sounding pitch is C4 (60).
        assertEquals(67, ukulele.strings[0].openMidi)
        val order = ukulele.stringsByPitch(listOf(0, 0, 0, 3))
        assertEquals(listOf(1, 2, 0, 3), order)

        val cMajor = VoicingGenerator.generate(ukulele, chord(0, ChordQuality.MAJOR))
            .single { it.isPrimary }
        assertEquals(0, pitchClassOf(cMajor.soundingMidi.min()), "the lowest pitch is not the root")
    }

    @Test
    fun aSlashChordPutsTheRequiredNoteInTheBass() {
        val slash = ChordName(root = 0, quality = ChordQuality.MAJOR, bass = 4)
        val voicings = VoicingGenerator.generate(guitar, slash)
        assertTrue(voicings.isNotEmpty(), "no shape for C/E")
        for (voicing in voicings) {
            assertEquals(4, pitchClassOf(voicing.soundingMidi.min()), "bass wrong in ${voicing.frets}")
        }
    }

    @Test
    fun mutedStringsAreMarkedRatherThanSilentlyDropped() {
        val d = VoicingGenerator.generate(guitar, chord(2, ChordQuality.MAJOR))
            .single { it.isPrimary }
        assertEquals(MUTED_FRET, d.frets[0])
        assertEquals(MUTED_FRET, d.frets[1])
        assertEquals(6, d.frets.size)
    }

    @Test
    fun aFourStringInstrumentKeepsAllFourTonesOfASeventh() {
        for (root in 0..11) {
            val name = chord(root, ChordQuality.DOMINANT_SEVENTH)
            for (voicing in VoicingGenerator.generate(ukulele, name)) {
                val classes = voicing.soundingMidi.map { pitchClassOf(it) }.toSet()
                assertEquals(4, name.pitchClasses.size)
                assertTrue(
                    classes.containsAll(name.pitchClasses),
                    "dropped a tone from a four-note chord on a four-string instrument",
                )
            }
        }
    }

    @Test
    fun anUnreachableTuningYieldsNothingRatherThanANonsenseShape() {
        val impossible = guitar.copy(
            strings = List(6) { StringSpec(openMidi = 40, label = "E") },
            fretCount = 1,
        )
        assertTrue(VoicingGenerator.generate(impossible, chord(0, ChordQuality.MAJOR_SEVENTH)).isEmpty())
    }
}
