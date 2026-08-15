package com.vayunmathur.games.alchemist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.games.alchemist.data.AlchemyItem
import com.vayunmathur.games.alchemist.platform.AlchemistAchievementsManager
import com.vayunmathur.games.alchemist.platform.AlchemistViewModel
import com.vayunmathur.games.alchemist.platform.AppBackupAgent
import com.vayunmathur.games.alchemist.ui.CollectionPage
import com.vayunmathur.games.alchemist.ui.HomePage
import com.vayunmathur.games.alchemist.ui.ItemDetailsPage
import com.vayunmathur.games.alchemist.ui.components.UnlockNotification
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.GameHubComposeHook
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Navigation(viewModel: AlchemistViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    val achievementsManager = rememberAchievementsManager()
    GameHubComposeHook("alchemist", achievementsManager)
    val newAchievement = achievementsManager?.newAchievement?.collectAsState()?.value
    var showingUnlock by remember { mutableStateOf(false) }
    var currentUnlocks by remember { mutableStateOf(emptyList<AlchemyItem>()) }
    LaunchedEffect(achievementsManager) {
        if (achievementsManager != null) {
            launch { achievementsManager.checkExistingAchievements() }
            viewModel.bindAchievements(achievementsManager)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.newUnlocksEvent.collectLatest { items ->
            currentUnlocks = items
            showingUnlock = true
            delay(3000)
            showingUnlock = false
        }
    }
    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Home> { HomePage(backStack, viewModel, onOpenCollection = { backStack.add(Route.Collection) }, onOpenGameCenter = { backStack.add(Route.GameCenter) }) }
            entry<Route.Collection> { CollectionPage(backStack, viewModel) }
            entry<Route.ItemDetails> { ItemDetailsPage(backStack, viewModel, it.item) }
            entry<Route.GameCenter> { achievementsManager?.let { GameCenterScreen(backupAgent = AppBackupAgent(), manager = it, onBack = { backStack.pop() }) } }
        }
        newAchievement?.let { ach -> AchievementNotification(ach) { achievementsManager.dismissNotification() } }
        UnlockNotification(unlock = currentUnlocks, showing = showingUnlock)
    }
}

@Composable
fun rememberAchievementsManager(): AchievementsManager? {
    val context = LocalContext.current
    val state = produceState<AchievementsManager?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { val json = context.assets.open("achievements.json").bufferedReader().use { it.readText() }; AlchemistAchievementsManager(context, json) }
    }
    return state.value
}