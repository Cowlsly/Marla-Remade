package com.vayunmathur.files.platform

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the standalone TextEditorActivity.
 *
 * Owns the asynchronous read/write of the file content. The composable continues to manage
 * its own [androidx.compose.foundation.text.input.TextFieldState] (cursor/selection) and the
 * edit/preview toggle UI flag, since those are pure UI state.
 */
class TextEditorViewModel(application: Application) : AndroidViewModel(application) {

    /** Last persisted file content; `null` while still loading. */
    private val _initialContent = MutableStateFlow<String?>(null)
    val initialContent: StateFlow<String?> = _initialContent.asStateFlow()

    fun load(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader().readText()
                } ?: ""
            } catch (_: Exception) {
                ""
            }
            _initialContent.value = text
        }
    }

    fun save(uri: Uri, content: String) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.writer().use { w -> w.write(content) }
                }
            } catch (_: Exception) {
                return@launch
            }
            _initialContent.value = content
        }
    }
}
