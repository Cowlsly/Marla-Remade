package com.vayunmathur.cast.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.CastDeviceKind
import com.vayunmathur.cast.domain.CastPhase
import com.vayunmathur.library.ui.ExternalIntents
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "CastVM"

/**
 * How long the toolbar reports "searching".
 *
 * mDNS keeps answering for as long as it is asked, so there is no point at which discovery is
 * genuinely finished; this is just how long a receiver on the network takes to answer, after
 * which a still-empty list means something is wrong rather than slow.
 */
private const val SCAN_INDICATOR_MS = 6_000L

/**
 * ViewModel for the Cast app.
 *
 * Owns no session state: [CastController] does, because the session outlives this ViewModel and
 * is shared with `CastService`. What lives here is what belongs to the screen - whether a scan is
 * running.
 */
class CastViewModel(application: Application) : AndroidViewModel(application), CastActions {

    private val appContext: Context get() = getApplication()
    private val discovery get() = CastController.discovery(appContext)

    private val _isScanning = MutableStateFlow(false)

    private var scanJob: Job? = null

    private val discoveryState = combine(
        discovery.devices,
        _isScanning,
        discovery.localNetworkBlocked,
    ) { devices, scanning, blocked ->
        // Sorted so the list does not reshuffle every time a device re-announces itself.
        Triple(devices.sortedBy { it.friendlyName.lowercase() }, scanning, blocked)
    }

    val uiState: StateFlow<CastUiState> = combine(
        discoveryState,
        CastController.device,
        CastController.isConnecting,
        CastController.sessionState,
    ) { (devices, scanning, blocked), device, connecting, session ->
        CastUiState(
            devices = devices,
            isScanning = scanning,
            localNetworkBlocked = blocked,
            connectedDevice = device,
            connection = when {
                session.phase == CastPhase.Failed -> CastConnection.Failed
                connecting -> CastConnection.Connecting
                device != null && session.phase == CastPhase.Ready -> CastConnection.Connected
                else -> CastConnection.Disconnected
            },
            // A speaker or a group has no screen, so only audio can go to it. Known from the
            // mDNS capability bitmask before anything is negotiated, which is what lets the UI
            // say "audio only" while the receiver is still being joined.
            audioOnly = device != null && device.kind != CastDeviceKind.Tv,
            volumeLevel = session.volumeLevel,
            muted = session.muted,
            failure = failureMessage(session.failure),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CastUiState())

    override fun startScan() {
        // Restart rather than ignore: the refresh button has to do something when the browse is
        // already running, and a second collector on the same manager would double every device.
        scanJob?.cancel()
        _isScanning.value = true
        discovery.clear()
        scanJob = viewModelScope.launch {
            // The browse itself keeps running for as long as the screen is open, so devices
            // appear and disappear as they are switched on and off. Only the "searching"
            // indicator is time-boxed - left on it would spin forever and the refresh button
            // would never come back.
            launch {
                delay(SCAN_INDICATOR_MS)
                _isScanning.value = false
            }
            try {
                discovery.discover().collect { }
            } catch (e: Exception) {
                Log.w(TAG, "discovery ended", e)
            }
            _isScanning.value = false
        }
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    override fun connect(device: CastDevice) = CastController.connect(appContext, device)

    override fun disconnect() = CastController.disconnect(appContext)

    override fun setVolume(level: Double) = CastController.setVolume(level)

    override fun setMuted(muted: Boolean) = CastController.setMuted(muted)

    /**
     * Turn a `LAUNCH_ERROR` reason into something worth reading.
     *
     * The reasons are wire constants and must not reach the screen. `NOT_FOUND` is what a TV
     * answers when asked for the audio-only receiver and `SYSTEM_ERROR` is what a speaker answers
     * when asked for the audio-video one, so in practice both mean "this device will not run what
     * we asked it to" - which is the same sentence to a user either way.
     */
    private fun failureMessage(reason: String?): String? = when (reason) {
        null -> null
        "NOT_FOUND", "INVALID_APP_ID" -> appContext.getString(R.string.cast_launch_unsupported)
        "CANCELLED" -> appContext.getString(R.string.cast_launch_cancelled)
        else -> appContext.getString(R.string.cast_launch_failed)
    }

    /**
     * There is no intent for the local-network toggle itself, so this opens the app's own
     * settings page, where the permission lives.
     */
    override fun openLocalNetworkSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${appContext.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ExternalIntents.launch(appContext, intent)
    }
}

class CastViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CastViewModel::class.java)) {
            "Unknown ViewModel class $modelClass"
        }
        return CastViewModel(application) as T
    }
}
