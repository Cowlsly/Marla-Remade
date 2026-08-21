package com.vayunmathur.cast.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.CastPhase
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "CastVM"

/** What the Default Media Receiver is given when the URI has no type of its own. */
private const val FALLBACK_MIME_TYPE = "video/mp4"

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
 * running and which source the user has chosen but not yet cast.
 *
 * A source picked before a device is deliberately kept rather than rejected: "choose a file, then
 * choose a screen" is the order the share sheet forces anyway, so both orders work and
 * [CastUiState.pendingSource] is cast automatically as soon as a receiver is joined.
 */
class CastViewModel(application: Application) : AndroidViewModel(application), CastActions {

    private val appContext: Context get() = getApplication()
    private val discovery get() = CastController.discovery(appContext)

    private val _isScanning = MutableStateFlow(false)
    private val _pendingSource = MutableStateFlow<CastSource?>(null)

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
        _pendingSource,
    ) { (devices, scanning, blocked), device, connecting, session, pending ->
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
            pendingSource = pending,
            playerState = session.playerState,
            title = session.title,
            positionSec = session.currentTimeSec,
            durationSec = session.durationSec,
            volumeLevel = session.volumeLevel,
            muted = session.muted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CastUiState())

    init {
        // Cast whatever is waiting the moment a receiver is joined, so the two "pick" steps can
        // happen in either order.
        viewModelScope.launch {
            CastController.sessionState.collect { state ->
                if (state.phase != CastPhase.Ready) return@collect
                val source = _pendingSource.value ?: return@collect
                _pendingSource.value = null
                dispatchSource(source)
            }
        }
    }

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

    override fun pickLocalFile(uri: String) {
        val parsed = uri.toUri()
        val mimeType = appContext.contentResolver.getType(parsed) ?: FALLBACK_MIME_TYPE
        val label = displayName(appContext, parsed) ?: parsed.lastPathSegment ?: uri
        setSource(CastSource.LocalFile(uri, label, mimeType))
    }

    override fun castUrl(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            AppMessages.show(appContext.getString(R.string.cast_url_invalid))
            return
        }
        val label = trimmed.toUri().lastPathSegment?.takeIf { it.isNotBlank() } ?: trimmed
        setSource(CastSource.RemoteUrl(trimmed, label, guessMimeType(trimmed)))
    }

    override fun clearPendingSource() {
        _pendingSource.value = null
    }

    override fun play() = CastController.play()

    override fun pause() = CastController.pause()

    override fun stopPlayback() = CastController.stopPlayback()

    override fun seek(positionSec: Double) = CastController.seek(positionSec)

    override fun setVolume(level: Double) = CastController.setVolume(level)

    override fun setMuted(muted: Boolean) = CastController.setMuted(muted)

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

    /** THROWAWAY (Phase 0). Needs a device, so it reuses the one already selected. */
    override fun spikeMirror(appId: String) {
        // CastController rather than uiState: stateIn stops updating when nothing is collecting.
        val device = CastController.device.value ?: return
        CastController.spikeMirror(appContext, device, appId)
    }

    /** Cast now if a receiver is joined, otherwise hold it until one is. */
    private fun setSource(source: CastSource) {
        if (uiState.value.connection == CastConnection.Connected) {
            dispatchSource(source)
        } else {
            _pendingSource.value = source
        }
    }

    private fun dispatchSource(source: CastSource) {
        when (source) {
            is CastSource.LocalFile -> CastController.castLocalFile(
                appContext,
                source.uri.toUri(),
                source.mimeType,
                source.label,
            )
            is CastSource.RemoteUrl ->
                CastController.castUrl(source.url, source.mimeType, source.label)
        }
    }

    /**
     * Best guess from the extension, because a remote URL's real type is not known until the
     * receiver fetches it - and the receiver needs a `contentType` in the LOAD request.
     */
    private fun guessMimeType(url: String): String = when {
        url.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
        url.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
        url.endsWith(".aac", ignoreCase = true) -> "audio/aac"
        url.endsWith(".flac", ignoreCase = true) -> "audio/flac"
        url.endsWith(".wav", ignoreCase = true) -> "audio/wav"
        url.endsWith(".webm", ignoreCase = true) -> "video/webm"
        url.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
        url.endsWith(".jpg", ignoreCase = true) || url.endsWith(".jpeg", ignoreCase = true) ->
            "image/jpeg"
        url.endsWith(".png", ignoreCase = true) -> "image/png"
        url.endsWith(".m3u8", ignoreCase = true) -> "application/x-mpegurl"
        else -> FALLBACK_MIME_TYPE
    }
}

internal fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
} catch (_: Exception) {
    null
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
