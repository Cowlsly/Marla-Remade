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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.IntentHelper
import com.vayunmathur.library.util.onFileDrop
import com.vayunmathur.notes.data.NotesRepository
import com.vayunmathur.notes.platform.NotesViewModel
import com.vayunmathur.notes.platform.NotesViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
