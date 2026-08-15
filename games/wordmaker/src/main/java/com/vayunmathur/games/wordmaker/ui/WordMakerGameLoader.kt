package com.vayunmathur.games.wordmaker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.Route
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.platform.WordMakerViewModel
import com.vayunmathur.games.wordmaker.rememberAchievementsManager
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.util.NavBackStack

@Composable
fun WordMakerGameLoader(backStack: NavBackStack<Route>, viewModel: WordMakerViewModel) {
    val currentLevel by viewModel.currentLevel.collectAsState()
    val crosswordData by viewModel.crosswordData.collectAsState()
    val error by viewModel.error.collectAsState()
    val gameMode by viewModel.gameMode.collectAsState()
    val competitiveActive by viewModel.competitiveActive.collectAsState()

    val achievementsManager = rememberAchievementsManager(viewModel.levelDataStore)
    if (achievementsManager == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val newAchievement by achievementsManager.newAchievement.collectAsState()

    LaunchedEffect(Unit) {
        achievementsManager.checkExistingAchievements()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            gameMode == GameMode.COMPETITIVE && !competitiveActive -> {
                CompetitiveLobbyPage(
                    viewModel = viewModel,
                    onOpenGameCenter = { backStack.add(Route.GameCenter) },
                    onOpenSettings = { backStack.add(Route.Settings) }
                )
            }

            error != null -> {
                Text(text = error!!, color = MaterialTheme.colorScheme.error)
            }

            crosswordData != null -> {
                WordGamePage(
                    crosswordData = crosswordData!!,
                    currentLevel = currentLevel,
                    viewModel = viewModel,
                    achievementsManager = achievementsManager,
                    onOpenGameCenter = { backStack.add(Route.GameCenter) },
                    onOpenSettings = { backStack.add(Route.Settings) }
                )
            }

            else -> {
                CircularProgressIndicator()
            }
        }

        newAchievement?.let {
            AchievementNotification(it) {
                achievementsManager.dismissNotification()
            }
        }
    }
}