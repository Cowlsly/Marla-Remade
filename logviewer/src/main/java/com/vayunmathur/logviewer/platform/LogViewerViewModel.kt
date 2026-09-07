package com.vayunmathur.logviewer.platform

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.logviewer.R
import com.vayunmathur.logviewer.domain.LogDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintStream

/**
 * Holds the loaded log and the parts of the screen the user changes: the description and the font
 * size.
 *
 * The app this replaces kept these in a process-wide `LruCache` keyed by a UUID it stashed in the
 * saved instance state, because a plain `Activity` loses its fields on rotation. A ViewModel is
 * what that cache was standing in for, so the cache is gone.
 *
 * Loading happens here, off the main thread. The original ran `logcat` inline in `onCreate`, which
 * is a multi-megabyte read and an exec on the UI thread; the output is identical either way, so
 * this is the one behavioural difference and it is in the user's favour.
 */
internal class LogViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(LogViewerUiState())
    val state: StateFlow<LogViewerUiState> = _state.asStateFlow()

    private var document: LogDocument? = null
    private var started = false

    /** The parts of the chrome that depend on what was actually loaded. */
    data class Chrome(
        val showReportButton: Boolean = false,
        val extraActions: List<ExtraAction> = emptyList(),
        val logcat: LogcatFilters? = null,
    )

    fun start(loader: suspend () -> LogLoadResult, chrome: suspend (LogDocument) -> Chrome) {
        if (started) return
        started = true
        viewModelScope.launch {
            // Both run on IO: `chrome` decides whether "More info" applies, which means reading
            // /data/tombstones.
            val outcome = withContext(Dispatchers.IO) {
                when (val result = loader()) {
                    is LogLoadResult.Unavailable -> result to null
                    is LogLoadResult.Loaded -> result to chrome(result.document)
                }
            }
            when (val result = outcome.first) {
                is LogLoadResult.Unavailable -> _state.update {
                    it.copy(
                        loading = false,
                        unavailable = true,
                        unavailableMessageRes = result.messageRes,
                    )
                }

                is LogLoadResult.Loaded -> {
                    val loaded = result.document
                    document = loaded
                    val resolved = outcome.second ?: Chrome()
                    _state.value = LogViewerUiState(
                        loading = false,
                        title = loaded.title,
                        sourcePackage = loaded.sourcePackage,
                        lines = loaded.displayLines(""),
                        kind = loaded.kind,
                        fontSizeSp = loaded.kind.initialFontSizeSp,
                        canCopy = loaded.canCopy,
                        showReportButton = resolved.showReportButton,
                        snapshotFileName = LogDocument.snapshotFileName(loaded.title),
                        logcat = resolved.logcat,
                        extraActions = resolved.extraActions,
                    )
                }
            }
        }
    }

    fun setDescription(description: String) {
        val loaded = document ?: return
        _state.update {
            it.copy(description = description, lines = loaded.displayLines(description))
        }
    }

    fun zoom(factor: Float) {
        _state.update { it.copy(fontSizeSp = (it.fontSizeSp * factor).coerceIn(MIN_SP, MAX_SP)) }
    }

    fun dismissStackTrace() = _state.update { it.copy(stackTrace = null) }

    /** The clipboard text, or null if nothing is loaded. Truncation is reported by the caller. */
    fun clipText(): LogDocument.ClipText? = document?.clipText(_state.value.description)

    fun snapshotBytes(): ByteArray? =
        document?.snapshotText(_state.value.description)?.toByteArray(Charsets.UTF_8)

    /**
     * Writes the log to the document the user picked.
     *
     * Failures are shown as a dialog with the stack trace in it rather than a one-line message,
     * because the interesting part of "could not write" is which exception it was.
     */
    fun save(uri: Uri) {
        val bytes = snapshotBytes() ?: return
        val fileName = _state.value.snapshotFileName
        val context = getApplication<Application>()
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: return@withContext OpenFailed
                    stream.use { it.write(bytes) }
                    null
                } catch (e: Exception) {
                    e
                }
            }
            when (failure) {
                null -> AppMessages.show(context.getString(R.string.toast_saved, fileName))
                OpenFailed -> AppMessages.show(context.getString(R.string.toast_unable_to_open_file))
                else -> _state.update { it.copy(stackTrace = stackTraceOf(failure)) }
            }
        }
    }

    private companion object {
        const val MIN_SP = 2f
        const val MAX_SP = 24f

        /** A `null` ParcelFileDescriptor is not an exception, but it is still a failure. */
        val OpenFailed = Exception()

        fun stackTraceOf(t: Throwable): String {
            val out = java.io.ByteArrayOutputStream(1000)
            t.printStackTrace(PrintStream(out))
            return out.toString()
        }
    }
}
