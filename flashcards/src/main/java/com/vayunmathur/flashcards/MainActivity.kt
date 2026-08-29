package com.vayunmathur.flashcards

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.data.FlashcardsRepository
import com.vayunmathur.flashcards.ui.DeckListPage
import com.vayunmathur.flashcards.ui.NoteEditPage
import com.vayunmathur.flashcards.ui.NoteListPage
import com.vayunmathur.flashcards.ui.NoteTypeEditPage
import com.vayunmathur.flashcards.ui.NoteTypeListPage
import com.vayunmathur.flashcards.ui.ReviewPage
import com.vayunmathur.flashcards.ui.SettingsPage
import com.vayunmathur.flashcards.ui.StatsPage
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.flashcards.util.FlashcardsViewModelFactory
import com.vayunmathur.flashcards.util.ThemeMode
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconDashboard
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconStyle
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val viewModel: FlashcardsViewModel by viewModels {
        FlashcardsViewModelFactory(application, FlashcardsRepository.get(application))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> null
            }
            DynamicTheme(darkTheme = darkTheme) { Navigation(viewModel) }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Main : Route

    @Serializable
    data object Stats : Route

    @Serializable
    data object NoteTypeList : Route

    @Serializable
    data class NoteTypeEdit(val noteTypeId: Long) : Route

    @Serializable
    data class CardList(val deckId: Long) : Route

    @Serializable
    data class NoteEdit(val deckId: Long, val noteId: Long) : Route

    @Serializable
    data class Review(
        val deckId: Long,
        val mode: Int = 0,
        val count: Int = 20,
        val daysAhead: Int = 3,
        val tags: List<String> = emptyList(),
    ) : Route
}

@Composable
fun Navigation(viewModel: FlashcardsViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Main)
    MainNavigation(backStack) {
        entry<Route.Main> { FlashcardsTabs(backStack, viewModel) }
        entry<Route.Stats> { StatsPage(backStack, viewModel) }
        entry<Route.NoteTypeList> { NoteTypeListPage(backStack, viewModel) }
        entry<Route.NoteTypeEdit> { NoteTypeEditPage(backStack, viewModel, it.noteTypeId) }
        entry<Route.CardList> { NoteListPage(backStack, viewModel, it.deckId) }
        entry<Route.NoteEdit> { NoteEditPage(backStack, viewModel, it.deckId, it.noteId) }
        entry<Route.Review>(metadata = FullscreenPage()) { ReviewPage(backStack, viewModel, it.deckId, it.mode, it.count, it.daysAhead, it.tags) }
    }
}

/**
 * The three bottom-nav tabs, hosted in a swipeable pager (see [TabbedPagerScaffold]).
 * NoteTypeList, NoteTypeEdit, CardList, NoteEdit and Review are pushed on top of this host
 * as ordinary routes. Stats is kept as a standalone pushed route because
 * NoteListPage.openStats() pushes Route.Stats via backStack.add().
 */
@Composable
private fun FlashcardsTabs(
    backStack: NavBackStack<Route>,
    viewModel: FlashcardsViewModel,
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val tabs = listOf(
        PagerTab(stringResource(R.string.nav_decks), { IconStyle() }) { DeckListPage(backStack, viewModel) },
        PagerTab(stringResource(R.string.nav_stats), { IconDashboard() }) { StatsPage(backStack, viewModel) },
        PagerTab(stringResource(R.string.nav_settings), { IconSettings() }) { SettingsPage(backStack, viewModel) },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
