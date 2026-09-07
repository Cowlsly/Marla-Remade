package com.vayunmathur.logviewer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDescription
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconTune
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.OverflowMenu
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.logviewer.R
import com.vayunmathur.logviewer.domain.LogDocument
import com.vayunmathur.logviewer.platform.ExtraAction
import com.vayunmathur.logviewer.platform.LogViewerActions
import com.vayunmathur.logviewer.platform.LogViewerUiState
import com.vayunmathur.logviewer.ui.components.LogLineList
import com.vayunmathur.logviewer.ui.dialogs.LogBuffersDialog
import com.vayunmathur.logviewer.ui.dialogs.LogLevelDialog
import com.vayunmathur.logviewer.ui.dialogs.StackTraceDialog
import com.vayunmathur.logviewer.ui.dialogs.TextInputDialog

/**
 * The one screen: a log, the actions that operate on it, and the filters that produced it.
 *
 * Stateless, so both entry points render it from their own [LogViewerUiState] and nothing about a
 * crash report or a logcat dump is baked into the layout.
 *
 * The bottom row is a pair of either/or buttons, which is inherited and is a real decision worth
 * keeping. Copy becomes Save once the log is too large for a clipboard transaction, so the button
 * offered is always one that will work; Share becomes Report when the sender says the report is
 * worth filing, which puts the tracker one tap away at the moment the user is looking at the crash.
 */
@Composable
internal fun LogViewerScreen(state: LogViewerUiState, actions: LogViewerActions) {
    val scrollBehavior = appBarScrollBehavior()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDescription by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showLevel by remember { mutableStateOf(false) }
    var showBuffers by remember { mutableStateOf(false) }

    // This app has no MainNavigation to drain AppMessages, so the one screen it has does it.
    LaunchedEffect(snackbarHostState) {
        AppMessages.messages.collect { message ->
            snackbarHostState.showSnackbar(message.text)
        }
    }

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LogDocument.MIME_TYPE)
    ) { uri -> if (uri != null) actions.save(uri) }

    // A log is read from the end, so that is where it opens. Only for logcat: a stack trace is read
    // from the top.
    LaunchedEffect(state.loading) {
        if (!state.loading && state.kind.scrollToBottom && state.lines.isNotEmpty()) {
            listState.scrollToItem(state.lines.lastIndex)
        }
    }
    // A description is appended to the end, and writing one you cannot see is pointless.
    LaunchedEffect(state.description) {
        if (state.description.isNotEmpty() && state.lines.isNotEmpty()) {
            listState.scrollToItem(state.lines.lastIndex)
        }
    }

    AppScaffold(
        title = state.title,
        scrollBehavior = scrollBehavior,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        actions = {
            if (!state.loading && !state.unavailable) {
                IconButton(onClick = { showDescription = true }) { IconDescription() }
                if (state.logcat != null) {
                    IconButton(onClick = { showFilter = true }) { IconSearch() }
                    IconButton(onClick = { showLevel = true }) { IconTune() }
                }
                if (state.showReportButton || state.canCopy || state.logcat != null) {
                    // OverflowMenu defaults to IconMenu(), a hamburger. This is an overflow, not a
                    // drawer.
                    OverflowMenu(icon = { IconMoreVert() }) {
                        // Share and Save appear here only when the bottom row gave their slot to
                        // Report and Copy - never twice.
                        if (state.showReportButton) {
                            Item(stringResource(R.string.action_share)) { actions.share() }
                        }
                        if (state.canCopy) {
                            Item(stringResource(R.string.action_save)) {
                                save.launch(state.snapshotFileName)
                            }
                        }
                        if (state.logcat != null) {
                            Item(stringResource(R.string.log_buffers)) { showBuffers = true }
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (!state.loading && !state.unavailable) {
                BottomActions(state, actions, onSave = { save.launch(state.snapshotFileName) })
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))

            state.unavailable -> {
                val messageRes = state.unavailableMessageRes
                // A failure with no message is already closing the window (see LogViewerPage).
                // Rendering the fallback text here would flash the wrong reason on the way out.
                if (messageRes != null) {
                    ErrorState(
                        title = stringResource(messageRes),
                        modifier = Modifier.padding(padding),
                    )
                }
            }

            else -> LogLineList(
                lines = state.lines,
                kind = state.kind,
                fontSizeSp = state.fontSizeSp,
                listState = listState,
                contentPadding = padding,
                onZoom = actions::zoom,
                // The 16dp gutter the app being replaced put around its whole layout. It stays on
                // the list rather than the window so the top bar and the snackbar still go
                // edge to edge.
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    if (showDescription) {
        val titleRes =
            if (state.description.isEmpty()) R.string.add_description
            else R.string.update_description
        TextInputDialog(
            title = stringResource(titleRes),
            initialText = state.description,
            onConfirm = {
                actions.setDescription(it.trim())
                showDescription = false
            },
            onDismiss = { showDescription = false },
        )
    }

    val logcat = state.logcat
    if (logcat != null) {
        if (showFilter) {
            TextInputDialog(
                title = stringResource(R.string.set_filter),
                initialText = logcat.filterRegex,
                onConfirm = {
                    showFilter = false
                    // Applying an empty filter to an already-unfiltered log would be a new activity
                    // showing the same thing.
                    if (it.isNotEmpty() || logcat.filterRegex.isNotEmpty()) actions.setFilter(it)
                },
                onDismiss = { showFilter = false },
                singleLine = true,
                hint = stringResource(R.string.set_filter_editor_hint),
            )
        }
        if (showLevel) {
            LogLevelDialog(
                current = logcat.level,
                onSelect = {
                    showLevel = false
                    actions.setLevel(it)
                },
                onDismiss = { showLevel = false },
            )
        }
        if (showBuffers) {
            LogBuffersDialog(
                current = logcat.buffers,
                onApply = actions::setBuffers,
                onDismiss = { showBuffers = false },
            )
        }
    }

    val stackTrace = state.stackTrace
    if (stackTrace != null) {
        StackTraceDialog(
            stackTrace = stackTrace,
            onCopy = actions::copyStackTrace,
            onDismiss = actions::dismissStackTrace,
        )
    }
}

@Composable
private fun BottomActions(
    state: LogViewerUiState,
    actions: LogViewerActions,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (state.canCopy) {
            Button(onClick = actions::copy) { Text(stringResource(R.string.action_copy)) }
        } else {
            Button(onClick = onSave) { Text(stringResource(R.string.action_save)) }
        }
        if (state.showReportButton) {
            Button(onClick = actions::report) { Text(stringResource(R.string.action_report)) }
        } else {
            Button(onClick = actions::share) { Text(stringResource(R.string.action_share)) }
        }
        for (extra in state.extraActions) {
            Button(onClick = { actions.perform(extra) }) { Text(stringResource(extra.labelRes)) }
        }
    }
}

private val ExtraAction.labelRes: Int
    get() = when (this) {
        ExtraAction.MoreInfo -> R.string.action_more_info
        ExtraAction.ShowAppLog -> R.string.action_show_log
        ExtraAction.ShowSystemLog -> R.string.action_show_system_log
    }
