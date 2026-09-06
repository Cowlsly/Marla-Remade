package com.vayunmathur.tuner.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Instrument.soundingRange] against the shipped catalogue.
 *
 * This is the number the NNLS dictionary is built from, so getting it wrong is not a cosmetic
 * error: too wide and the octave-below phantom that eats a chord's fifth comes back, too narrow
 * and a note the player can actually reach becomes unreportable.
 */
class InstrumentRangeTest {
    private val catalog = InstrumentCatalog.parse(
        File("src/main/assets/instruments.json").readText(),
    )

    @Test
    fun aFrettedRangeSpansOpenStringsToTheHighestFret() {
        // Low E2 open, high E4 plus fifteen frets.
        assertEquals(40..79, catalog.byId("guitar-standard").soundingRange)
        // C4 open, A4 plus fifteen frets. Note the low bound is the *second* string: GCEA is
        // re-entrant, so the range cannot be read off string index zero.
        assertEquals(60..84, catalog.byId("ukulele-gcea").soundingRange)
    }

    @Test
    fun aStringedInstrumentSoundsOneNotePerString() {
        assertEquals(4, catalog.byId("ukulele-gcea").polyphony)
        assertEquals(6, catalog.byId("guitar-standard").polyphony)
        assertEquals(KEYBOARD_POLYPHONY, catalog.byId("piano").polyphony)
    }

    @Test
    fun aKeyboardRangeIsItsCompass() {
        val piano = catalog.byId("piano")
        assertEquals(piano.keyboard!!.lowestMidi..piano.keyboard!!.highestMidi, piano.soundingRange)
    }

    @Test
    fun noInstrumentCanSoundAnOctaveBelowItsOwnFloor() {
        for (instrument in catalog.instruments) {
            val range = instrument.soundingRange
            assertTrue(range.first <= range.last, "${instrument.id} has an empty range")
            if (instrument.strings.isEmpty()) continue
            assertEquals(
                instrument.strings.minOf { it.openMidi },
                range.first,
                "${instrument.id} floor is not its lowest open string",
            )
        }
    }
}
