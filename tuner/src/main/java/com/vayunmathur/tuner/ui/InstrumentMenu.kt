package com.vayunmathur.tuner.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.tuner.R
import com.vayunmathur.tuner.platform.TunerUiState

/**
 * Chooses the instrument, which sets the analysis range and decides whether the Chord tab draws
 * a keyboard or a fret diagram.
 */
@Composable
fun InstrumentMenu(state: TunerUiState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(instrumentLabel(state.instrument.familyId)))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.instruments.forEach { instrument ->
                DropdownMenuItem(
                    text = { Text(stringResource(instrumentLabel(instrument.familyId))) },
                    onClick = {
                        onSelect(instrument.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * The one place adding a whole new instrument *family* needs a code change.
 *
 * A new tuning of an existing family is pure data - the UI labels it from the string letters -
 * but a JSON file cannot add a string resource, and hardcoding the name would be worse.
 */
@StringRes
internal fun instrumentLabel(familyId: String): Int = when (familyId) {
    "ukulele" -> R.string.instrument_ukulele
    "piano" -> R.string.instrument_piano
    else -> R.string.instrument_guitar
}
