package com.vayunmathur.nowplaying.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the app is currently doing, as far as the user is concerned. */
enum class DetectionStatus {
    /** Not listening. */
    Idle,

    /** Listening, and no music is being heard. */
    Silent,

    /** Listening, and music is being heard. */
    Music,

    /** Listening was requested but the model could not be loaded. */
    Unavailable,
}

/**
 * Process-wide bridge from the listener service to the UI.
 *
 * The service outlives the Activity, so state lives here rather than in a ViewModel, and the UI
 * observes it. Nothing binds to the service.
 */
object ListeningController {
    private val _status = MutableStateFlow(DetectionStatus.Idle)
    val status: StateFlow<DetectionStatus> = _status.asStateFlow()

    internal fun publish(status: DetectionStatus) {
        _status.value = status
    }
}
