package com.vayunmathur.games.minesweeper

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.vayunmathur.games.minesweeper.platform.AppBackupAgent
import com.vayunmathur.games.minesweeper.platform.MinesweeperViewModel
import com.vayunmathur.games.minesweeper.ui.GameScreen
import com.vayunmathur.games.minesweeper.ui.HomeScreen
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.util.GameHubComposeHook
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: MinesweeperViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    val newAchievement by viewModel.achievementsManager.newAchievement.collectAsState()
    GameHubComposeHook("minesweeper", viewModel.achievementsManager)
    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Home> { HomeScreen(backStack, viewModel) }
            entry<Route.Game> { GameScreen(backStack, viewModel, it.config) }
            entry<Route.GameCenter> {
                GameCenterScreen(
                    backupAgent = AppBackupAgent(),
                    manager = viewModel.achievementsManager,
                    onBack = { backStack.pop() },
                )
            }
        }
        newAchievement?.let {
            AchievementNotification(it) { viewModel.dismissAchievementNotification() }
        }
    }
}
