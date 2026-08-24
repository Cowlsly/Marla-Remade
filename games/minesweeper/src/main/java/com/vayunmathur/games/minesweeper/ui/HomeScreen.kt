package com.vayunmathur.games.minesweeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.games.minesweeper.R
import com.vayunmathur.games.minesweeper.Route
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.platform.MinesweeperViewModel
import com.vayunmathur.games.minesweeper.ui.dialogs.GameConfigDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack

@Composable
fun HomeScreen(
    backStack: NavBackStack<Route>,
    viewModel: MinesweeperViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showConfig by remember { mutableStateOf(false) }

    AppScaffold(
        title = stringResource(R.string.app_name),
        actions = {
            IconButton(onClick = { backStack.add(Route.GameCenter) }) { IconEmojiEvents() }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (viewModel.hasActiveGame()) {
                Button(
                    onClick = { backStack.add(Route.Game(uiState.config)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.continue_game)) }
            }
            Button(
                onClick = { showConfig = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.new_game)) }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                BoardSize.entries.forEach { size ->
                    SizeStatsCard(size, viewModel, Modifier.weight(1f))
                }
            }
        }
    }

    if (showConfig) {
        GameConfigDialog(
            // Seeded with the last thing played, so a player grinding one setting taps twice.
            initial = uiState.config,
            onStart = { config ->
                showConfig = false
                viewModel.newGame(config)
                backStack.add(Route.Game(config))
            },
            onDismiss = { showConfig = false },
        )
    }
}

@Composable
private fun SizeStatsCard(
    size: BoardSize,
    viewModel: MinesweeperViewModel,
    modifier: Modifier = Modifier,
) {
    val stats = viewModel.getSizeStats(size)
    Card(modifier) {
        Column(
            Modifier
                .padding(Spacing.md)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                size.displayName(),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "${stats.gamesWon}/${stats.gamesPlayed}",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.cleared_count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (stats.bestTimeSeconds < Int.MAX_VALUE) {
                Text(
                    formatTime(stats.bestTimeSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
