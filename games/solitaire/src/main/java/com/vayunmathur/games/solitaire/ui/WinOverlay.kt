package com.vayunmathur.games.solitaire.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.solitaire.R
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.game.GameResultOverlay
import com.vayunmathur.library.ui.game.formatDuration

/**
 * Solitaire's win card.
 *
 * A thin wrapper over the shared [GameResultOverlay] — solitaire used to carry its own scrim alpha,
 * corner radius and type sizes, which drifted from the other games for no reason anyone could point at.
 */
@Composable
fun WinOverlay(
    elapsedSeconds: Int,
    moveCount: Int,
    onNewGame: () -> Unit,
    onBack: () -> Unit
) {
    GameResultOverlay(
        title = stringResource(R.string.congratulations),
        won = true,
        onPlayAgain = onNewGame,
        onBack = onBack,
        playAgainLabel = stringResource(R.string.new_game),
        backLabel = stringResource(R.string.back),
    ) {
        Text(
            "${stringResource(R.string.time)}: ${formatDuration(elapsedSeconds)}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "${stringResource(R.string.moves)}: $moveCount",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
