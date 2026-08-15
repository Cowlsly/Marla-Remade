package com.vayunmathur.games.pipes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.games.pipes.Route
import com.vayunmathur.games.pipes.data.LevelPack
import com.vayunmathur.games.pipes.domain.DailyLevels
import com.vayunmathur.games.pipes.platform.DailyProgress
import com.vayunmathur.games.pipes.platform.PackProgress
import com.vayunmathur.games.pipes.platform.PipesViewModel
import com.vayunmathur.library.util.NavBackStack

@Composable
fun PackScreen(backStack: NavBackStack<Route>, viewModel: PipesViewModel, onOpenGameCenter: () -> Unit) {
    val levelStats by viewModel.levelStats.collectAsState()
    val dailyCompleted by viewModel.dailyCompleted.collectAsState()
    val dailyDay by viewModel.dailyDay.collectAsState()
    val dailyStreak by viewModel.dailyStreak.collectAsState()
    PackListScreen(
        daily = DailyProgress(day = dailyDay, completed = dailyCompleted, total = DailyLevels.LEVELS_PER_DAY, streak = dailyStreak),
        packs = LevelPack.PACKS.map { pack -> PackProgress(name = pack.name, shape = pack.shape, completed = pack.levels.count { levelStats.containsKey(it.id) }, total = pack.levels.size) },
        onOpenDaily = { backStack.add(Route.DailySelector) },
        onOpenPack = { backStack.add(Route.LevelSelector(it)) },
        onOpenSettings = { backStack.add(Route.Settings) },
        onOpenGameCenter = onOpenGameCenter,
    )
}
