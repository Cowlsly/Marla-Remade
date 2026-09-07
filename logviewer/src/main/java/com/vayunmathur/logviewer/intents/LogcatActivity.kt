package com.vayunmathur.logviewer.intents

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.logviewer.domain.LogLevel
import com.vayunmathur.logviewer.platform.ExtraAction
import com.vayunmathur.logviewer.platform.LogViewerController
import com.vayunmathur.logviewer.platform.LogViewerViewModel
import com.vayunmathur.logviewer.platform.LogcatActivityExtras
import com.vayunmathur.logviewer.platform.LogcatFilters
import com.vayunmathur.logviewer.platform.LogcatReader
import com.vayunmathur.logviewer.ui.LogViewerPage

/**
 * Shows the device log, or one app's slice of it.
 *
 * Reached through `ACTION_LOGCAT` and `ACTION_PKG_LOGCAT`, both guarded by
 * `com.vayunmathur.logviewer.SHOW_LOGCAT` (signature|privileged), so only another privileged app
 * signed with the same key can open it. There is no launcher entry: the log is something another
 * system surface sends the user to, not something to browse to.
 *
 * The whole activity is one snapshot of the log with one set of filters. Changing a filter starts
 * another one - see [LogViewerController].
 */
class LogcatActivity : ComponentActivity() {

    private val viewModel: LogViewerViewModel by viewModels()

    // One instance, not one per recomposition of setContent's lambda.
    private val actions by lazy { Actions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = readRequest(intent)
        // Read here rather than in the loader: a secondary user may see their own apps' logs but
        // not the device log, and the answer must not depend on which thread asks.
        val isSystemUser = getSystemService(UserManager::class.java)?.isSystemUser == true
        val context: Context = applicationContext

        viewModel.start(
            loader = { LogcatReader.read(context, request) },
            chrome = {
                LogViewerViewModel.Chrome(
                    showReportButton = false,
                    extraActions =
                        if (request.targetPackage != null && isSystemUser) {
                            listOf(ExtraAction.ShowSystemLog)
                        } else {
                            emptyList()
                        },
                    logcat = LogcatFilters(
                        buffers = request.buffers,
                        level = request.level,
                        filterRegex = request.filterRegex,
                    ),
                )
            },
        )

        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                LogViewerPage(
                    viewModel = viewModel,
                    actions = actions,
                    onFinish = { finishAndRemoveTask() },
                )
            }
        }
    }

    private fun readRequest(intent: Intent): LogcatReader.Request = LogcatReader.Request(
        // Set by the framework for ACTION_PKG_LOGCAT, and by our own "Show log" button.
        targetPackage = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME),
        buffers = intent.getStringArrayListExtra(LogcatActivityExtras.LOG_BUFFERS)
            ?: LogcatReader.DEFAULT_BUFFERS,
        level = LogLevel.ofPriority(
            intent.getIntExtra(LogcatActivityExtras.LOG_LEVEL, LogLevel.Verbose.priority)
        ),
        filterRegex = intent.getStringExtra(LogcatActivityExtras.FILTER_REGEX).orEmpty(),
    )

    private inner class Actions : LogViewerController(this@LogcatActivity, viewModel) {
        override fun perform(action: ExtraAction) {
            if (action != ExtraAction.ShowSystemLog) return
            // No extras: the system log with the default buffers and no filter.
            start(Intent(this@LogcatActivity, LogcatActivity::class.java))
        }
    }
}
