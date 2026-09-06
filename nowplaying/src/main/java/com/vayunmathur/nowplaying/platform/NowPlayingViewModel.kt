package com.vayunmathur.nowplaying.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.nowplaying.data.DetectionHistory
import com.vayunmathur.nowplaying.service.ListenerService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NowPlayingViewModel(app: Application) : AndroidViewModel(app) {
    private val history = DetectionHistory.get(app)

    val state: StateFlow<NowPlayingUiState> = combine(
        ListeningController.status,
        history.recent(),
    ) { status, events ->
        NowPlayingUiState(status = status, history = events)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NowPlayingUiState())

    fun setListening(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled) ListenerService.start(app) else ListenerService.stop(app)
    }

    fun clearHistory() {
        viewModelScope.launch { history.clear() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
