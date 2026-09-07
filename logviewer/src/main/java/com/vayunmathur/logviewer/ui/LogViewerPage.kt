package com.vayunmathur.logviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.logviewer.platform.LogViewerActions
import com.vayunmathur.logviewer.platform.LogViewerViewModel

/**
 * Binds [LogViewerViewModel] to [LogViewerScreen].
 *
 * Also owns the one thing the screen cannot decide: when there is nothing to show and no reason to
 * give, the window closes rather than sitting there empty. That is the app being replaced's
 * `finishAndRemoveTask()`, and it matters that it removes the task - with
 * `documentLaunchMode="always"` a merely-finished activity would leave an empty card in Recents.
 */
@Composable
internal fun LogViewerPage(
    viewModel: LogViewerViewModel,
    actions: LogViewerActions,
    onFinish: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.unavailable, state.unavailableMessageRes) {
        if (state.unavailable && state.unavailableMessageRes == null) onFinish()
    }

    LogViewerScreen(state = state, actions = actions)
}
