package com.vayunmathur.games.wordmaker

import androidx.compose.runtime.Composable
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import com.vayunmathur.games.wordmaker.platform.AppBackupAgent
import com.vayunmathur.games.wordmaker.platform.WordMakerAchievementsManager
import com.vayunmathur.games.wordmaker.platform.WordMakerViewModel
import com.vayunmathur.games.wordmaker.ui.CompetitiveLobbyPage
import com.vayunmathur.games.wordmaker.ui.SettingsPage
import com.vayunmathur.games.wordmaker.ui.WordMakerGameLoader
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.GameHubSessionHook

@Composable
fun Navigation(viewModel: WordMakerViewModel) {
    val b=rememberNavBackStack<Route>(Route.Game)
    b.openSettingsIfRequested(Route.Settings)
    GameHubSessionHook("wordmaker","Wordmaker")
    MainNavigation(b){
        entry<Route.Game>(metadata = FullscreenPage()){ WordMakerGameLoader(b,viewModel) }
        entry<Route.GameCenter>{
            val g=rememberAchievementsManager(viewModel.levelDataStore)
            if(g!=null) GameCenterScreen(backupAgent=AppBackupAgent(), manager=g, onBack={b.pop()})
        }
        entry<Route.Settings>{ SettingsPage(viewModel=viewModel,onBack={b.pop()}) }
    }
}
@Composable
fun rememberAchievementsManager(levelDataStore: LevelDataStore): AchievementsManager? {
    val c=androidx.compose.ui.platform.LocalContext.current
    val s=androidx.compose.runtime.produceState<AchievementsManager?>(null,c,levelDataStore){ value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){ val j=c.assets.open("achievements.json").bufferedReader().use{it.readText()}; WordMakerAchievementsManager(c,j,levelDataStore)} }
    return s.value
}