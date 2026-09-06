package com.vayunmathur.tuner.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.tuner.data.AnalysisRange
import com.vayunmathur.tuner.data.Instrument
import com.vayunmathur.tuner.data.InstrumentKind
import com.vayunmathur.tuner.data.KeyboardSpec
import com.vayunmathur.tuner.data.StringSpec
import com.vayunmathur.tuner.domain.TuningBand
import com.vayunmathur.tuner.platform.NoteReadout
import com.vayunmathur.tuner.platform.NoteUiState
import com.vayunmathur.tuner.platform.TunerUiState

/** Phone-shaped, roughly 1080x2340 at xxhdpi - comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store-listing images for `:tuner`, rendered from Compose previews.
 *
 * An instrumented test is not an option here: the screen is driven by live microphone audio,
 * and there is no way to feed a device a guitar. Literal state is the only reproducible source,
 * which is exactly why the screen is stateless.
 *
 * Only the Note tab is shown, because only the Note tab exists. The Chord tab is a placeholder
 * and screenshotting it would advertise a feature the app does not have.
 */
class MetadataPreviews {

    private val guitar = Instrument(
        id = "guitar-standard",
        familyId = "guitar",
        kind = InstrumentKind.FRETTED,
        strings = listOf(
            StringSpec(40, "E"),
            StringSpec(45, "A"),
            StringSpec(50, "D"),
            StringSpec(55, "G"),
            StringSpec(59, "B"),
            StringSpec(64, "E"),
        ),
        fretCount = 15,
        analysis = AnalysisRange(70.0, 1400.0),
        isDefaultForFamily = true,
    )

    private val piano = Instrument(
        id = "piano",
        familyId = "piano",
        kind = InstrumentKind.KEYBOARD,
        keyboard = KeyboardSpec(21, 108),
        analysis = AnalysisRange(27.0, 4200.0),
        capoSupported = false,
        isDefaultForFamily = true,
    )

    private fun noteState(
        instrument: Instrument,
        readout: NoteReadout,
    ) = TunerUiState(
        instrument = instrument,
        instruments = listOf(guitar, piano),
        listening = true,
        note = NoteUiState(readout = readout),
    )

    @PreviewTest
    @Preview(name = "1-note-in-tune", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1NoteInTune() {
        DynamicTheme(darkTheme = true) {
            NoteScreen(
                state = noteState(
                    guitar,
                    NoteReadout(
                        midi = 40,
                        letter = "E",
                        octave = 2,
                        frequencyHz = 82.44,
                        cents = 0.7,
                        band = TuningBand.IN_TUNE,
                    ),
                ),
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-note-flat", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2NoteFlat() {
        DynamicTheme(darkTheme = true) {
            NoteScreen(
                state = noteState(
                    guitar,
                    NoteReadout(
                        midi = 55,
                        letter = "G",
                        octave = 3,
                        frequencyHz = 195.32,
                        cents = -6.0,
                        band = TuningBand.OUT,
                    ),
                ),
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-note-piano-reference", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3NotePianoReference() {
        DynamicTheme(darkTheme = true) {
            NoteScreen(
                state = noteState(
                    piano,
                    NoteReadout(
                        midi = 69,
                        letter = "A",
                        octave = 4,
                        frequencyHz = 441.6,
                        cents = 2.3,
                        band = TuningBand.CLOSE,
                    ),
                ),
            )
        }
    }
}
