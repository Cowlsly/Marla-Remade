package com.vayunmathur.cast.platform

import com.vayunmathur.cast.domain.CastDevice

/**
 * Where the session is, from the point of view of a screen.
 *
 * Flatter than `CastPhase`: a screen does not care whether LAUNCH or the second CONNECT is
 * outstanding, only whether the receiver is joined yet.
 */
enum class CastConnection { Disconnected, Connecting, Connected, Failed }

/**
 * Everything the Cast screen draws.
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
    /**
     * True when the target has no screen, so only audio can be sent to it.
     *
     * Derived from the mDNS capability bitmask, which is known before anything is negotiated -
     * that is what lets the UI say "audio only" while the receiver is still being joined.
     */
    val audioOnly: Boolean = false,
    val volumeLevel: Double = 1.0,
    val muted: Boolean = false,
    /** Why the receiver refused, already turned into something worth showing a user. */
    val failure: String? = null,
)

interface CastActions {
    fun startScan() {}
    fun stopScan() {}
    fun connect(device: CastDevice) {}
    fun disconnect() {}
    fun setVolume(level: Double) {}
    fun setMuted(muted: Boolean) {}
    fun openLocalNetworkSettings() {}

    companion object {
        val Noop: CastActions = object : CastActions {}
    }
}
