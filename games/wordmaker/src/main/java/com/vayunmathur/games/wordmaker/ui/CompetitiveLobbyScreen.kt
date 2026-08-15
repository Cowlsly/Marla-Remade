package com.vayunmathur.games.wordmaker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.platform.CompetitiveLobbyActions
import com.vayunmathur.games.wordmaker.platform.CompetitiveLobbyUiState
import com.vayunmathur.games.wordmaker.platform.WordMakerViewModel
import com.vayunmathur.games.wordmaker.ui.components.DifficultyDropdown
import com.vayunmathur.games.wordmaker.ui.components.WordMakerTopBar
import com.vayunmathur.library.ui.ExperimentalMaterial3Api

fun CompetitiveLobbyPage(
    viewModel: WordMakerViewModel,
    onOpenGameCenter: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val gameMode by viewModel.gameMode.collectAsState()
    val difficulty by viewModel.difficulty.collectAsState()
    val score by viewModel.competitiveScore.collectAsState()
    val result by viewModel.competitiveResult.collectAsState()

    CompetitiveLobbyScreen(
        state = CompetitiveLobbyUiState(
            gameMode = gameMode,
            difficulty = difficulty,
            score = score,
            result = result
        ),
        actions = viewModel,
        onOpenGameCenter = onOpenGameCenter,
        onOpenSettings = onOpenSettings
    )
}


fun CompetitiveLobbyScreen(
    state: CompetitiveLobbyUiState,
    actions: CompetitiveLobbyActions,
    onOpenGameCenter: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val difficulty = state.difficulty
    val result = state.result

    Scaffold(
        topBar = {
            WordMakerTopBar(
                gameMode = state.gameMode,
                onModeSelected = { actions.setGameMode(it) },
                onOpenGameCenter = onOpenGameCenter,
                onOpenSettings = onOpenSettings
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.competitive_score, state.score),
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )

            result?.let {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = if (it.won) {
                        stringResource(R.string.competitive_solved, it.delta)
                    } else {
                        stringResource(R.string.competitive_timed_out, it.delta)
                    },
                    color = if (it.won) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            }

            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.competitive_difficulty), fontWeight = FontWeight.Bold)
            DifficultyDropdown(selected = difficulty, onSelected = { actions.setDifficulty(it) })
            Text(stringResource(R.string.competitive_time_limit, difficulty.timeLimitSeconds))

            Spacer(Modifier.height(32.dp))
            Button(onClick = { actions.loadNextCompetitiveLevel() }) {
                Text(
                    stringResource(
                        if (result == null) R.string.competitive_start else R.string.next_level
                    )
                )
            }
        }
    }
}
