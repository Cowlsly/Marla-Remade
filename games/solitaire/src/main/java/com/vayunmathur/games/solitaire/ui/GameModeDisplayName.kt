package com.vayunmathur.games.solitaire.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.solitaire.R
import com.vayunmathur.games.solitaire.data.GameMode

@Composable
fun GameMode.displayName(): String = when (this) {
    GameMode.KLONDIKE -> stringResource(R.string.klondike)
    GameMode.SPIDER -> stringResource(R.string.spider)
    GameMode.FREECELL -> stringResource(R.string.freecell)
    GameMode.PYRAMID -> stringResource(R.string.pyramid)
}
