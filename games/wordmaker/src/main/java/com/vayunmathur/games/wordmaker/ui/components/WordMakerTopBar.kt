package com.vayunmathur.games.wordmaker.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.data.Difficulty
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.library.ui.CenterAlignedTopAppBar
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.TextButton

fun GameModeDropdown(selected: GameMode, onSelected: (GameMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(gameModeLabel(selected)), fontWeight = FontWeight.Bold)
            Text("  Γû╛", fontWeight = FontWeight.Bold)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GameMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(gameModeLabel(mode))) },
                    onClick = {
                        expanded = false
                        onSelected(mode)
                    }
                )
            }
        }
    }
}

private fun gameModeLabel(mode: GameMode) = when (mode) {
    GameMode.CASUAL -> R.string.mode_casual
    GameMode.COMPETITIVE -> R.string.mode_competitive
    GameMode.DAILY -> R.string.mode_daily
}


private fun gameModeLabel(mode: GameMode) = when (mode) {
    GameMode.CASUAL -> R.string.mode_casual
    GameMode.COMPETITIVE -> R.string.mode_competitive
    GameMode.DAILY -> R.string.mode_daily
}

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
        Text(stringResource(R.string.daily_streak, streak.toInt()))
    }
}


fun DifficultyDropdown(selected: Difficulty, onSelected: (Difficulty) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun label(d: Difficulty) = when (d) {
        Difficulty.EASY -> R.string.difficulty_easy
        Difficulty.MEDIUM -> R.string.difficulty_medium
        Difficulty.HARD -> R.string.difficulty_hard
    }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(label(selected)))
            Text(" Γû╛")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Difficulty.values().forEach { d ->
                DropdownMenuItem(
                    text = { Text(stringResource(label(d))) },
                    onClick = {
                        expanded = false
                        onSelected(d)
                    }
                )
            }
        }
    }
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
                if (gameMode == GameMode.CASUAL && levelNumber != null) {
                    Text(
                        text = stringResource(R.string.level_number, levelNumber),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            navigationIcon = {
                GameModeDropdown(selected = gameMode, onSelected = onModeSelected)
            },
            actions = {
                IconButton(onClick = onOpenGameCenter) {
                    Icon(painterResource(id = android.R.drawable.btn_star_big_on), "Achievements")
                }
                IconButton(onClick = onOpenSettings) {
                    IconSettings()
                }
            }
        )
}


fun CompetitiveStatusBar(
    score: Int,
    remainingTimeMs: Long
) {
    val totalSeconds = (remainingTimeMs / 1000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val timeColor = if (totalSeconds <= 10) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.competitive_score, score))
        Spacer(Modifier.weight(1f))
        Text(
            text = "%d:%02d".format(minutes, seconds),
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = timeColor
        )
    }
}
