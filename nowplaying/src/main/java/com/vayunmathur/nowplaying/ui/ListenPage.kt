package com.vayunmathur.nowplaying.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.nowplaying.platform.NowPlayingActions
import com.vayunmathur.nowplaying.platform.NowPlayingViewModel

@Composable
fun ListenPage(viewModel: NowPlayingViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ListenScreen(
        state = state,
        actions = NowPlayingActions(
            onListeningChange = viewModel::setListening,
            onClearHistory = viewModel::clearHistory,
        ),
    )
}
