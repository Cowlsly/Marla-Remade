package com.vayunmathur.games.minesweeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.minesweeper.R
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.TapMode
import com.vayunmathur.games.minesweeper.platform.MinesweeperActions
import com.vayunmathur.games.minesweeper.platform.MinesweeperUiState
import com.vayunmathur.games.minesweeper.ui.components.MineFieldGrid
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconFlag
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior

/** Caps the field on tablets, where a full-width board would give absurdly large cells. */
private val FieldMaxWidth = 480.dp

/**
 * The board, with no dependency on the ViewModel so it can be rendered from a `@Preview` — see
 * `src/screenshotTest`.
 */
@Composable
fun GameBoardScreen(
    state: MinesweeperUiState,
    actions: MinesweeperActions,
    onExit: () -> Unit,
) {
    val game = state.game
    val mineCount = state.config.difficulty.mineCount(state.config.size)
    AppScaffold(
        // Size name plus the mine count rather than size plus density name: the two enums share
        // labels ("Medium" and "Medium"), and the raw count is what a player actually wants to know.
        title = "${state.config.size.displayName()} · " +
            pluralStringResource(R.plurals.mine_count, mineCount, mineCount),
        onNavigateBack = onExit,
        alignment = AppBarAlignment.Center,
        actions = {
            if (game != null) {
                Text(
                    formatTime(game.elapsedSeconds),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = Spacing.lg),
                )
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (game == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Box
            }

            Column(
                Modifier
                    .widthIn(max = FieldMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconFlag(tint = MaterialTheme.colorScheme.error)
                    Text(
                        " ${game.minesRemaining}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                MineFieldGrid(
                    game = game,
                    onTap = actions::tapCell,
                    onLongPress = actions::flagCell,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!game.started) {
                    Text(
                        stringResource(R.string.how_to_play),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TapModeToggle(state.tapMode, actions::setTapMode)

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Button(
                        onClick = { actions.restart() },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.new_field_button)) }
                    TextButton(
                        onClick = {
                            actions.giveUp()
                            onExit()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.give_up)) }
                }
            }

            if (game.isOver) {
                ResultOverlay(
                    won = game.outcome == GameOutcome.WON,
                    elapsedSeconds = game.elapsedSeconds,
                    onPlayAgain = { actions.restart() },
                    onBack = onExit,
                )
            }
        }
    }
}

/**
 * Chooses what a plain tap does.
 *
 * Two segments rather than a switch, because digging and flagging are peers: neither is the off state of
 * the other. Flag mode matters most on a dense field, where placing a run of flags by long press is
 * slow and one slip on a mine ends the game outright.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TapModeToggle(mode: TapMode, onSelect: (TapMode) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        val options = listOf(TapMode.DIG to R.string.mode_dig, TapMode.FLAG to R.string.mode_flag)
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                onClick = { onSelect(value) },
                selected = mode == value,
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun ResultOverlay(
    won: Boolean,
    elapsedSeconds: Int,
    onPlayAgain: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            // Scrim, so the card reads as modal instead of letting the field show around it.
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f))
            .padding(Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier.padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    stringResource(if (won) R.string.cleared else R.string.boom),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (won) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                // A losing time is not an achievement, so it is only shown on a win.
                if (won) Text("${stringResource(R.string.time)}: ${formatTime(elapsedSeconds)}")
                Button(onClick = onPlayAgain, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.play_again))
                }
                TextButton(onClick = onBack, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.back))
                }
            }
        }
    }
}
