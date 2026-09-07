package com.vayunmathur.logviewer.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.logviewer.R
import com.vayunmathur.logviewer.domain.LogDocument
import com.vayunmathur.logviewer.domain.LogLevel
import com.vayunmathur.logviewer.provider.BlobProvider

/**
 * The half of [LogViewerActions] that needs an Activity: the clipboard, the share sheet, and
 * starting the next screen.
 *
 * Changing a logcat filter starts a *new* activity carrying the edited intent rather than reloading
 * in place. That is what makes Back step through the filters the user tried, and with
 * `documentLaunchMode="always"` each one is its own entry in Recents - so two logs can be compared
 * side by side. Reloading in place would quietly lose both.
 */
internal open class LogViewerController(
    private val activity: ComponentActivity,
    private val viewModel: LogViewerViewModel,
) : LogViewerActions {

    override fun copy() {
        val clip = viewModel.clipText() ?: return
        copyToClipboard(viewModel.state.value.title, clip.text)
        AppMessages.show(
            activity.getString(
                if (clip.truncated) R.string.copied_to_clipboard_truncated
                else R.string.copied_to_clipboard
            )
        )
    }

    override fun share() {
        val bytes = viewModel.snapshotBytes() ?: return
        val fileName = viewModel.state.value.snapshotFileName
        val uri = BlobProvider.getUri(fileName, bytes)
        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            type = LogDocument.MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, fileName))
    }

    /**
     * Copies first, then opens the tracker. Filing an issue means pasting the log into it, and a
     * browser opening over the report is the last moment the user could still have copied it.
     */
    override fun report() {
        copy()
        activity.startActivity(Intent(Intent.ACTION_VIEW, ISSUES_URL.toUri()))
    }

    override fun save(uri: Uri) = viewModel.save(uri)

    override fun setDescription(description: String) = viewModel.setDescription(description)

    override fun zoom(factor: Float) = viewModel.zoom(factor)

    override fun setBuffers(buffers: List<String>) = relaunch {
        putStringArrayListExtra(LogcatActivityExtras.LOG_BUFFERS, ArrayList(buffers))
    }

    override fun setLevel(level: LogLevel) = relaunch {
        putExtra(LogcatActivityExtras.LOG_LEVEL, level.priority)
    }

    override fun setFilter(regex: String) = relaunch {
        putExtra(LogcatActivityExtras.FILTER_REGEX, regex)
    }

    /** Nothing by default; each entry point overrides the actions it actually offers. */
    override fun perform(action: ExtraAction) = Unit

    override fun dismissStackTrace() = viewModel.dismissStackTrace()

    override fun copyStackTrace() {
        val stackTrace = viewModel.state.value.stackTrace ?: return
        copyToClipboard(null, stackTrace)
    }

    protected fun start(intent: Intent) = activity.startActivity(intent)

    private fun relaunch(edit: Intent.() -> Unit) {
        activity.startActivity(Intent(activity.intent).apply(edit))
    }

    private fun copyToClipboard(label: CharSequence?, text: String) {
        activity.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private companion object {
        const val ISSUES_URL = "https://github.com/vayun-mathur/Modern-Apps/issues"
    }
}

/**
 * Extras the logcat screen sends to itself.
 *
 * Unlike the names in `LogViewerIntents` these are private to the app - nothing outside it produces
 * them - so they are package-qualified to keep them out of anyone else's way.
 */
internal object LogcatActivityExtras {
    private const val PREFIX = "com.vayunmathur.logviewer.LogcatActivity"

    const val LOG_BUFFERS = "$PREFIX.LOG_BUFFERS"
    const val LOG_LEVEL = "$PREFIX.LOG_LEVEL"
    const val FILTER_REGEX = "$PREFIX.FILTER_REGEX"
}
