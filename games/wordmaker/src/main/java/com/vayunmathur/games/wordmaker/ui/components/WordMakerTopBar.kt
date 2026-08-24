package com.vayunmathur.games.wordmaker.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.data.Difficulty
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.library.ui.CenterAlignedTopAppBar
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.game.DailyStreakText
import com.vayunmathur.library.ui.game.GameModeChooser
import com.vayunmathur.library.ui.game.GameTopBarActions
import com.vayunmathur.library.ui.game.formatDuration

@Composable
fun DailyStatusBar(streak: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.daily_challenge), fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        DailyStreakText(streak)
    }
}

@Composable
fun DifficultyDropdown(selected: Difficulty, onSelected: (Difficulty) -> Unit) {
    GameModeChooser(
        selected = selected,
        options = Difficulty.entries,
        label = { stringResource(difficultyLabel(it)) },
        onSelect = onSelected,
        // Sits inline in the lobby rather than in an app bar, so it takes body type.
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

private fun difficultyLabel(difficulty: Difficulty) = when (difficulty) {
    Difficulty.EASY -> R.string.difficulty_easy
    Difficulty.MEDIUM -> R.string.difficulty_medium
    Difficulty.HARD -> R.string.difficulty_hard
}

private fun gameModeLabel(mode: GameMode) = when (mode) {
    GameMode.CASUAL -> R.string.mode_casual
    GameMode.COMPETITIVE -> R.string.mode_competitive
    GameMode.DAILY -> R.string.mode_daily
}

/**
 * The mode chooser wordmaker's screens put in their title slot.
 *
 * Wraps the shared [GameModeChooser] with wordmaker's own labels so its three screens cannot drift
 * apart again. [levelNumber] folds the level into the label for the ladder mode, which is why the bar
 * needs no separate title.
 */
@Composable
fun WordMakerModeChooser(
    selected: GameMode,
    onSelected: (GameMode) -> Unit,
    levelNumber: Int? = null,
) {
    GameModeChooser(
        selected = selected,
        options = GameMode.entries,
        label = { mode ->
            if (mode == GameMode.CASUAL && levelNumber != null) {
                stringResource(R.string.level_number, levelNumber)
            } else {
                stringResource(gameModeLabel(mode))
            }
        },
        menuLabel = { stringResource(gameModeLabel(it)) },
        onSelect = onSelected,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordMakerTopBar(
    gameMode: GameMode,
    onModeSelected: (GameMode) -> Unit,
    onOpenGameCenter: () -> Unit,
    onOpenSettings: () -> Unit,
    levelNumber: Int? = null
) {
        CenterAlignedTopAppBar(
            title = {
                WordMakerModeChooser(gameMode, onModeSelected, levelNumber)
            },
            actions = {
                GameTopBarActions(
                    onOpenGameCenter = onOpenGameCenter,
                    onOpenSettings = onOpenSettings,
                )
            }
        )
}


@Composable
fun CompetitiveStatusBar(
    score: Int,
    remainingTimeMs: Long
) {
    val totalSeconds = (remainingTimeMs / 1000L).toInt()
    val timeColor = if (totalSeconds <= URGENT_SECONDS) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.competitive_score, score))
        Spacer(Modifier.weight(1f))
        Text(
            text = formatDuration(totalSeconds),
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = timeColor
        )
    }
}

/** Where the countdown turns red, which is about as long as one more word takes. */
private const val URGENT_SECONDS = 10
