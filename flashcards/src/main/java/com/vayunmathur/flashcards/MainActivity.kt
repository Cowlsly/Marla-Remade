package com.vayunmathur.flashcards

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.MainNavigation
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
    data object DeckList : Route

    @Serializable
    data object Stats : Route

    @Serializable
    data object Settings : Route

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
    val backStack = rememberNavBackStack<Route>(Route.DeckList)
    MainNavigation(
        backStack = backStack,
        bottomBar = {
            val current = backStack.last()
            if (current is Route.DeckList || current is Route.Stats || current is Route.Settings) {
                BottomNavBar(
                    backStack = backStack,
                    pages = listOf(
                        BottomBarItem(stringResource(R.string.nav_decks), Route.DeckList) { IconStyle() },
                        BottomBarItem(stringResource(R.string.nav_stats), Route.Stats) { IconDashboard() },
                        BottomBarItem(stringResource(R.string.nav_settings), Route.Settings) { IconSettings() },
                    ),
                    currentPage = current,
                )
            }
        },
    ) {
        entry<Route.DeckList> { DeckListPage(backStack, viewModel) }
        entry<Route.Stats> { StatsPage(backStack, viewModel) }
        entry<Route.Settings> { SettingsPage(backStack, viewModel) }
        entry<Route.NoteTypeList> { NoteTypeListPage(backStack, viewModel) }
        entry<Route.NoteTypeEdit> { NoteTypeEditPage(backStack, viewModel, it.noteTypeId) }
        entry<Route.CardList> { NoteListPage(backStack, viewModel, it.deckId) }
        entry<Route.NoteEdit> { NoteEditPage(backStack, viewModel, it.deckId, it.noteId) }
        entry<Route.Review> { ReviewPage(backStack, viewModel, it.deckId, it.mode, it.count, it.daysAhead, it.tags) }
    }
}
