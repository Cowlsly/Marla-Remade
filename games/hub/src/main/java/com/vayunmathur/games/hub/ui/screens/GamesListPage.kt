package com.vayunmathur.games.hub.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.util.GameIconResolver
import com.vayunmathur.games.hub.util.GamesListActions
import com.vayunmathur.games.hub.util.GamesListUiState
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel

/** Binds [GameHubViewModel] to the stateless [GamesListScreen]. */
@Composable
fun GamesListPage(
    viewModel: GameHubViewModel,
    onGameClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val games by viewModel.gamesFlow.collectAsStateWithLifecycle()
    val allAchievements by viewModel.allAchievementsFlow.collectAsStateWithLifecycle()
    val dailyStreaks by viewModel.dailyStreaksFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val iconCache = remember { mutableMapOf<String, Drawable?>() }

    val installedGameIds = remember(games) {
        games.filter { g ->
            try { context.packageManager.getPackageInfo(g.packageName, 0); true } catch (_: Exception) { false }
        }.mapTo(mutableSetOf()) { it.gameId }
    }

    val achievementProgressByGame = remember(allAchievements) {
        allAchievements.groupBy { it.gameId }.mapValues { (_, list) -> list.count { it.isUnlocked } to list.size }
    }

    GamesListScreen(
        state = GamesListUiState(
            games = games,
            achievementProgressByGame = achievementProgressByGame,
            dailyStreakByGame = dailyStreaks,
            installedGameIds = installedGameIds,
        ),
        actions = object : GamesListActions {
            override fun openGame(gameId: String) = onGameClick(gameId)
            override fun playGame(game: HubGameEntity) = launchGame(context, game)
        },
        iconFor = { game -> iconCache.getOrPut(game.packageName) { GameIconResolver.resolveAppIcon(context, game.packageName) } },
        modifier = modifier,
    )
}
