package com.vayunmathur.games.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.IconDashboard
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconSportsEsports
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.games.hub.data.DB_NAME
import com.vayunmathur.games.hub.data.GamesHubRepository
import com.vayunmathur.games.hub.ui.screens.AchievementsScreen
import com.vayunmathur.games.hub.ui.screens.ActivityFeedScreen
import com.vayunmathur.games.hub.ui.screens.DashboardPage
import com.vayunmathur.games.hub.ui.screens.GameDetailScreen
import com.vayunmathur.games.hub.ui.screens.GamesListPage
import com.vayunmathur.games.hub.ui.screens.ProfilePage
import com.vayunmathur.games.hub.ui.screens.SettingsScreen
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.games.hub.viewmodel.GameHubViewModelFactory
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {

    private var dbConfigs: List<Pair<String, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        var ready by mutableStateOf(false)

        val repository = GamesHubRepository.get(application)
        dbConfigs = try {
            val pass = DatabaseHelper(this).getPassphrase()
            listOf(DB_NAME to pass)
        } catch (_: Exception) {
            emptyList()
        }
        val factory = GameHubViewModelFactory(application, repository)
        val vm: GameHubViewModel by viewModels { factory }

        ready = true

        setContent {
            DynamicTheme {
                if (ready) {
                    HubNavigation(vm, dbConfigs)
                } else {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Serializable
sealed interface MainRoute : NavKey {
    @Serializable data object Main : MainRoute
    @Serializable data class GameDetail(val gameId: String) : MainRoute
    @Serializable data class AchievementsForGame(val gameId: String) : MainRoute
    @Serializable data object Activity : MainRoute
    @Serializable data object Settings : MainRoute
}

@Composable
fun HubNavigation(
    viewModel: GameHubViewModel,
    dbConfigs: List<Pair<String, String>>
) {
    val backStack = rememberNavBackStack<MainRoute>(MainRoute.Main)

    MainNavigation(backStack) {
        entry<MainRoute.Main> {
            HubTabs(viewModel = viewModel, backStack = backStack, dbConfigs = dbConfigs)
        }
        entry<MainRoute.GameDetail>(metadata = MorphPage()) { route ->
            GameDetailScreen(
                gameId = route.gameId,
                viewModel = viewModel,
                backStack = backStack
            )
        }
        entry<MainRoute.AchievementsForGame> { route ->
            AchievementsScreen(viewModel = viewModel, initialGameFilter = route.gameId)
        }
        entry<MainRoute.Activity> {
            ActivityFeedScreen(
                viewModel = viewModel,
                backStack = backStack,
                onGameClick = { gid -> backStack.add(MainRoute.GameDetail(gid)) }
            )
        }
        entry<MainRoute.Settings> {
            SettingsScreen(
                viewModel = viewModel,
                backStack = backStack,
                dbConfigs = dbConfigs,
                datastoreNames = listOf("datastore_default")
            )
        }
    }
}

@Composable
private fun HubTabs(
    viewModel: GameHubViewModel,
    backStack: NavBackStack<MainRoute>,
    dbConfigs: List<Pair<String, String>>,
) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val tabs = listOf(
        PagerTab("Home", { IconDashboard() }) {
            DashboardPage(
                viewModel = viewModel,
                onGameClick = { gameId -> backStack.add(MainRoute.GameDetail(gameId)) },
                onProfileClick = { scope.launch { pagerState.animateScrollToPage(3) } },
                onActivityClick = { backStack.add(MainRoute.Activity) },
                onGamesClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                dbConfigs = dbConfigs,
                datastoreNames = listOf("datastore_default")
            )
        },
        PagerTab("Games", { IconSportsEsports() }) {
            GamesListPage(
                viewModel = viewModel,
                onGameClick = { gameId -> backStack.add(MainRoute.GameDetail(gameId)) }
            )
        },
        PagerTab("Achievements", { IconEmojiEvents() }) {
            AchievementsScreen(viewModel = viewModel)
        },
        PagerTab("Profile", { IconPerson() }) {
            ProfilePage(viewModel = viewModel)
        },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
