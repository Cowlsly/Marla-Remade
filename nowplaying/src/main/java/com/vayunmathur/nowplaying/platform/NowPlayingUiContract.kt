package com.vayunmathur.nowplaying.platform

import com.vayunmathur.nowplaying.data.DetectionEvent

/** Everything the listen screen draws. */
data class NowPlayingUiState(
    val status: DetectionStatus = DetectionStatus.Idle,
    val history: List<DetectionEvent> = emptyList(),
) {
    /** True only while the microphone is actually open. */
    val listening: Boolean
        get() = status == DetectionStatus.Silent || status == DetectionStatus.Music
}

/** Everything the listen screen can do. */
data class NowPlayingActions(
    val onListeningChange: (Boolean) -> Unit,
    val onClearHistory: () -> Unit,
)
