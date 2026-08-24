package com.vayunmathur.games.arrows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.arrows.R
import com.vayunmathur.games.arrows.data.GameMode
import com.vayunmathur.games.arrows.data.STARTING_HEARTS
import com.vayunmathur.games.arrows.platform.ArrowsGameActions
import com.vayunmathur.games.arrows.platform.ArrowsUiState
import com.vayunmathur.games.arrows.ui.components.ArrowsBoard
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior

/** Caps the board on tablets, where a full-width grid would give absurdly large cells. */
private val BoardMaxWidth = 420.dp

/**
 * The board, with no dependency on the ViewModel so it can be rendered from a `@Preview` — see
 * `src/screenshotTest`.
 */
@Composable
fun ArrowsGameScreen(
    state: ArrowsUiState,
    actions: ArrowsGameActions,
    onOpenSettings: () -> Unit,
    onOpenGameCenter: () -> Unit,
) {
    val daily = state.mode == GameMode.DAILY
    AppScaffold(
        title = {
            ModeDropdown(
                mode = state.mode,
                level = state.level,
                onSelect = actions::setGameMode,
            )
        },
        alignment = AppBarAlignment.Center,
        actions = {
            IconButton(onClick = onOpenGameCenter) { IconEmojiEvents() }
            IconButton(onClick = onOpenSettings) { IconSettings() }
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
                Text(
                    stringResource(R.string.daily_streak, state.dailyStreak),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    Modifier
                        .fillMaxWidth()
                        .padding(Spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                else -> {
                    Hearts(game.hearts)

                    ArrowsBoard(
                        game = game,
                        showRoutes = state.showRoutes,
                        onTapArrow = actions::tapArrow,
                        modifier = Modifier
                            .widthIn(max = BoardMaxWidth)
                            .fillMaxWidth(),
                    )

                    when {
                        game.isWon -> {
                            Text(
                                stringResource(R.string.board_cleared),
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
                            if (game.removed.isEmpty() && game.hearts == STARTING_HEARTS) {
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

/**
 * The hearts left.
 *
 * Spent hearts stay in place as hollow outlines rather than disappearing, so the row does not reflow
 * and the player can see at a glance how much margin is gone.
 */
@Composable
private fun Hearts(hearts: Int) {
    val description = pluralStringResource(R.plurals.cd_hearts, hearts.coerceAtLeast(0), hearts.coerceAtLeast(0))
    Row(
        Modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        repeat(STARTING_HEARTS) { index ->
            val spent = index >= hearts
            IconFavorite(
                Modifier.size(20.dp),
                tint = if (spent) MaterialTheme.colorScheme.surfaceContainerHighest
                else MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The title doubles as the mode switch.
 *
 * There is only one board, so putting the mode where the title would go keeps the app bar to a single
 * control rather than adding a tab row for two options.
 */
@Composable
private fun ModeDropdown(
    mode: GameMode,
    level: Int,
    onSelect: (GameMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                when (mode) {
                    GameMode.CASUAL -> stringResource(R.string.level_title, level)
                    GameMode.DAILY -> stringResource(R.string.mode_daily)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            IconArrowDropDown()
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in GameMode.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                when (option) {
                                    GameMode.CASUAL -> R.string.mode_casual
                                    GameMode.DAILY -> R.string.mode_daily
                                }
                            )
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
