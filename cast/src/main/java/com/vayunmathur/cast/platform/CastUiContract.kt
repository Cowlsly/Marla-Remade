package com.vayunmathur.cast.platform

import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.CastPlayerState

/**
 * Where the session is, from the point of view of a screen.
 *
 * Flatter than `CastPhase`: a screen does not care whether LAUNCH or the second CONNECT is
 * outstanding, only whether it can offer transport controls yet.
 */
enum class CastConnection { Disconnected, Connecting, Connected, Failed }

/** What the user has chosen to cast, before or after a device is picked. */
sealed interface CastSource {
    val label: String
    val mimeType: String

    /** A `content://` URI from the picker or the share sheet; served by `MediaFileServer`. */
    data class LocalFile(
        val uri: String,
        override val label: String,
        override val mimeType: String,
    ) : CastSource

    data class RemoteUrl(
        val url: String,
        override val label: String,
        override val mimeType: String,
    ) : CastSource
}

/**
 * Everything the Cast screens draw.
 *
 * Every field is defaulted so a `@Preview` can build one from the parts it cares about - which
 * is what the store-listing images are rendered from (see `src/screenshotTest`). It lives beside
 * the ViewModel so the dependency runs one way: `ui` depends on this, and the ViewModel
 * implements [CastActions].
 */
data class CastUiState(
    val devices: List<CastDevice> = emptyList(),
    val isScanning: Boolean = false,
    /**
     * Android 16+ blocked the mDNS browse. Distinct from an empty list, because the fix is a
     * permission rather than turning the TV on.
     */
    val localNetworkBlocked: Boolean = false,
    val connectedDevice: CastDevice? = null,
    val connection: CastConnection = CastConnection.Disconnected,
    /** Held until a device is picked, so choosing a file first is allowed. */
    val pendingSource: CastSource? = null,
    val playerState: CastPlayerState = CastPlayerState.Idle,
    val title: String? = null,
    val positionSec: Double = 0.0,
    /** Null for a live stream or until the receiver reports one. */
    val durationSec: Double? = null,
    val volumeLevel: Double = 1.0,
    val muted: Boolean = false,
) {
    /** Transport controls are meaningless without something loaded on the receiver. */
    val hasMedia: Boolean get() = playerState != CastPlayerState.Idle || title != null
}

interface CastActions {
    fun startScan() {}
    fun stopScan() {}
    fun connect(device: CastDevice) {}
    fun disconnect() {}
    fun pickLocalFile(uri: String) {}
    fun castUrl(url: String) {}
    fun clearPendingSource() {}
    fun play() {}
    fun pause() {}
    fun stopPlayback() {}
    fun seek(positionSec: Double) {}
    fun setVolume(level: Double) {}
    fun setMuted(muted: Boolean) {}
    fun openLocalNetworkSettings() {}

    /**
     * THROWAWAY (Phase 0): relaunch the connected device with a Cast Streaming app id and send
     * one OFFER, to find out whether an unregistered sender is allowed to do that.
     */
    fun spikeMirror(appId: String) {}

    companion object {
        val Noop: CastActions = object : CastActions {}
    }
}
