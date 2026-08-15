package com.vayunmathur.notes

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.IntentHelper
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.onFileDrop
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.notes.data.NotesRepository
import com.vayunmathur.notes.ui.NotePage
import com.vayunmathur.notes.ui.NotesListPage
import com.vayunmathur.notes.ui.ExternalNoteScreen
import com.vayunmathur.notes.util.NotesViewModel
import com.vayunmathur.notes.util.NotesViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private lateinit var notesRepository: NotesRepository
    private val notesViewModel: NotesViewModel by viewModels {
        NotesViewModelFactory(application, notesRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val ready = mutableStateOf(false)
        lifecycleScope.launch(Dispatchers.IO) {
            notesRepository = NotesRepository.get(applicationContext)
            val legacyNotes = notesRepository.readAndClearLegacyNotes()
            if (legacyNotes.isNotEmpty()) notesRepository.upsertAll(legacyNotes)
            withContext(Dispatchers.Main) {
                handleIntent(intent)
                ready.value = true
            }
        }

        setContent {
            DynamicTheme {
                Box(Modifier.fillMaxSize().onFileDrop { uris ->
                    notesViewModel.importFiles(uris)
                }) {
                    if (ready.value) Navigation(notesViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::notesRepository.isInitialized) handleIntent(intent)
    }

    /**
     * External VIEW/EDIT/SEND opens are shown on their own [ExternalNoteScreen]
     * (a standalone markdown editor) instead of being auto-added to the DB.
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris = IntentHelper.getUrisFromIntent(intent).ifEmpty { listOfNotNull(intent.data) }
        notesViewModel.openExternal(uris)
    }

    companion object {
        private const val TAG = "NotesMainActivity"
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object NotesList: Route
    @Serializable
    data class Note(val id: Long): Route
    @Serializable
    data class ExternalNote(val uri: String): Route
}

@Composable
fun Navigation(notesViewModel: NotesViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.NotesList)
    val externalOpens by notesViewModel.externalOpens.collectAsStateWithLifecycle()
    LaunchedEffect(externalOpens) {
        externalOpens.firstOrNull()?.let { uri ->
            notesViewModel.consumeExternal(uri)
            backStack.add(Route.ExternalNote(uri))
        }
    }
    MainNavigation(backStack) {
        entry<Route.NotesList>(metadata = ListPage()) {
            NotesListPage(backStack, notesViewModel)
        }
        entry<Route.Note>(metadata = ListDetailPage()) {
            NotePage(backStack, notesViewModel, it.id)
        }
        entry<Route.ExternalNote>(metadata = ListDetailPage()) {
            ExternalNoteScreen(backStack, notesViewModel, it.uri)
        }
    }
}
