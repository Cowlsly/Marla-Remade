package com.vayunmathur.cast.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.cast.platform.CastActions
import com.vayunmathur.cast.platform.CastUiState
import com.vayunmathur.library.ui.scrim

/** Dark enough to read the card against Settings, light enough to keep the page recognisable. */
private const val SCRIM_ALPHA = 0.6f

/**
 * The pair-code prompt as a popup, for a route selected in Settings.
 *
 * Settings' Cast page is a list of rows with nowhere to type six digits, so this is the surface that
 * takes them: the same [CastPairCodeCard] the app shows inline, drawn over whatever is underneath
 * rather than inside a screen of our own. Reusing the card is the point - there is one pairing UI and
 * one pairing mechanism behind it, and this only changes where they appear.
 *
 * The scrim is drawn here rather than by the window, because the hosting activity's theme is
 * transparent - see `Theme.CastPair`.
 */
@Composable
fun CastPairDialog(
    state: CastUiState,
    actions: CastActions,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .scrim { SCRIM_ALPHA }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Capped rather than full-width: six digits and two sentences stretched across a tablet
        // would read as a page rather than as the prompt it is.
        CastPairCodeCard(state = state, actions = actions, modifier = Modifier.widthIn(max = 420.dp))
    }
}
