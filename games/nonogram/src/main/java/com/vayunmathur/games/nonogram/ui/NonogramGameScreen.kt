package com.vayunmathur.games.nonogram.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.nonogram.R
import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import com.vayunmathur.games.nonogram.platform.NonogramGameActions
import com.vayunmathur.games.nonogram.platform.NonogramUiState
import com.vayunmathur.games.nonogram.ui.components.NonogramBoard
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.game.DailyStreakText
import com.vayunmathur.library.ui.game.GameModeChooser
import com.vayunmathur.library.ui.game.GameTopBarActions
import com.vayunmathur.library.ui.game.HeartsRow

/** Caps the board on tablets, where a full-width grid would give absurdly large cells. */
private val BoardMaxWidth = 460.dp

/**
 * Chooses what a plain tap does.
 *
 * Two segments rather than a single switch, because filling and crossing are peers: neither is the off
 * state of the other. The active one is filled in so the current mode is obvious at a glance, since
 * getting it wrong in fill mode costs a heart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkModeToggle(mode: MarkMode, onSelect: (MarkMode) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        val options = listOf(
            MarkMode.FILL to R.string.mode_fill,
            MarkMode.CROSS to R.string.mode_cross,
        )
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                onClick = { onSelect(value) },
                selected = mode == value,
            ) { Text(stringResource(label)) }
        }
    }
}

/**
 * The board, with no dependency on the ViewModel so it can be rendered from a `@Preview` — see
 * `src/screenshotTest`.
 */
@Composable
fun NonogramGameScreen(
    state: NonogramUiState,
    actions: NonogramGameActions,
    onOpenSettings: () -> Unit,
    onOpenGameCenter: () -> Unit,
) {
    val daily = state.mode == GameMode.DAILY
    AppScaffold(
        title = {
            GameModeChooser(
                selected = state.mode,
                options = GameMode.entries,
                // The ladder mode folds the level into its label, so the bar needs no second title.
                label = { mode ->
                    when (mode) {
                        GameMode.CASUAL -> stringResource(R.string.level_title, state.level)
                        GameMode.DAILY -> stringResource(R.string.mode_daily)
                    }
                },
                menuLabel = { mode ->
                    stringResource(
                        when (mode) {
                            GameMode.CASUAL -> R.string.mode_casual
                            GameMode.DAILY -> R.string.mode_daily
                        }
                    )
                },
                onSelect = actions::setGameMode,
            )
        },
        alignment = AppBarAlignment.Center,
        actions = {
            GameTopBarActions(
                onOpenGameCenter = onOpenGameCenter,
                onOpenSettings = onOpenSettings,
            )
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (daily) {
                DailyStreakText(state.dailyStreak)
            }

            val game = state.game
            when {
                state.generationFailed -> Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Text(
                        stringResource(R.string.generation_failed),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { actions.restartLevel() }) {
                        Text(stringResource(R.string.try_again))
                    }
                }

                game == null -> Box(
                    Modifier.fillMaxWidth().padding(Spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                else -> {
                    HeartsRow(remaining = game.hearts, total = STARTING_HEARTS)

                    NonogramBoard(
                        game = game,
                        onTap = actions::tapCell,
                        onLongPress = actions::crossCell,
                        modifier = Modifier
                            .widthIn(max = BoardMaxWidth)
                            .fillMaxWidth(),
                    )

                    when {
                        game.isWon -> {
                            Text(
                                stringResource(R.string.solved),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (daily) {
                                Text(
                                    stringResource(R.string.daily_come_back_tomorrow),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                Button(
                                    onClick = { actions.nextLevel() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.next_level)) }
                            }
                        }

                        game.isFailed -> {
                            Text(
                                stringResource(R.string.out_of_hearts),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(
                                onClick = { actions.restartLevel() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.try_again)) }
                        }

                        else -> {
                            MarkModeToggle(state.markMode, actions::setMarkMode)
                            if (game.filled.isEmpty() && game.crossed.isEmpty() &&
                                game.revealedBlanks.isEmpty()
                            ) {
                                Text(
                                    stringResource(R.string.how_to_play),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            OutlinedButton(onClick = { actions.restartLevel() }) {
                                Text(stringResource(R.string.restart))
                            }
                        }
                    }
                }
            }
        }
    }
}
