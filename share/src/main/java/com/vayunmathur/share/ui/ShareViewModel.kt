package com.vayunmathur.share.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.share.discovery.BleDiscoveryManager
import com.vayunmathur.share.discovery.DiscoverySource
import com.vayunmathur.share.discovery.NearbyDevice
import com.vayunmathur.share.discovery.NsdDiscoveryManager
import com.vayunmathur.share.protocol.PendingFile
import com.vayunmathur.share.protocol.ShareState
import com.vayunmathur.share.transfer.ShareTransferService
import com.vayunmathur.share.transport.Connection
import com.vayunmathur.share.transport.TcpTransport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ShareVM"

/**
 * Single ViewModel for the Share app (Receive + Send).
 *
 * BetoCore model (Everyone / public identity only):
 *  - Visibility: ServerSocket(0) on WiFi-LAN -> register _up._tcp with random 16-byte ServiceId
 *    and the ephemeral port, + BLE Nearby Presence advert under GATT 0xFCF1 whose bytes come from
 *    the Rust np_adv JNI (presence-derived device name, no plaintext endpoint_info on mDNS).
 *  - Discovery: browse _up._tcp (resolves host/port) + scan 0xFCF1 (presence name), then merge
 *    by endpointId; connect requires host/port from the _up._tcp leg.
 */
class ShareViewModel(
    application: Application,
) : AndroidViewModel(application), ShareActions {

    private val appContext: Context get() = getApplication()

    // --- Device identity ---

    private val _localName = MutableStateFlow(
        Build.MODEL?.takeIf { it.isNotBlank() } ?: "My device"
    )
    val localName: StateFlow<String> = _localName.asStateFlow()

    fun setLocalName(name: String) {
        _localName.value = name.trim().ifBlank { _localName.value }
    }

    // --- Subsystems (lazy so the VM can be constructed in previews / tests
    // without touching the framework) ------------------------------------

    private val nsd by lazy { NsdDiscoveryManager(appContext) }
    private val ble by lazy { BleDiscoveryManager(appContext) }
    // TcpTransport owns ServerSocket(0) on WiFi-LAN; NsdDiscoveryManager publishes its port under _up._tcp.
    private val transport by lazy { TcpTransport(localName = _localName.value) }

    // --- Receive: visibility toggle -------------------------------------

    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    val listenPort: StateFlow<Int?> by lazy {
        transport.listenPort
    }

    val incomingConnections: StateFlow<List<Connection>> by lazy {
        transport.incomingConnections
    }

    val receiveUiState: StateFlow<ReceiveUiState> by lazy {
        combine(
            _isVisible,
            _localName,
            transport.listenPort,
            transport.incomingConnections,
        ) { visible, name, port, conns ->
            ReceiveUiState(
                isVisible = visible,
                localName = name,
                listenPort = port,
                incomingConnections = conns,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiveUiState())
    }

    override fun setVisible(visible: Boolean) {
        _isVisible.value = visible
        if (visible) {
            val port = transport.listen()
            // Register _up._tcp with a random 16-byte ServiceId, publishing the WiFi-LAN port.
            nsd.advertise(localName.value, port)
            // BLE Nearby Presence advert under 0xFCF1 (bytes from Rust np_adv JNI, public mode).
            ble.startAdvertising(localName.value)
            ShareTransferService.startReceiveMode(appContext, port)
        } else {
            nsd.unadvertise()
            ble.stopAdvertising()
            transport.stopListening()
            ShareTransferService.stop(appContext)
        }
    }

    override fun acceptIncoming(connection: Connection, accept: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val destDir = File(appContext.getExternalFilesDir(null), "Share/Received").also { it.mkdirs() }
            val rc = transport.acceptIncoming(connection, destDir, accept)
            if (rc < 0) {
                Log.w(TAG, "acceptIncoming failed rc=$rc")
            }
        }
    }

    // --- Send: discovery + outgoing URIs --------------------------------

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<NearbyDevice>> = _discoveredDevices.asStateFlow()

    private val _outgoingUris = MutableStateFlow<List<Uri>>(emptyList())
    val outgoingUris: StateFlow<List<Uri>> = _outgoingUris.asStateFlow()

    private val _outgoingDisplayNames = MutableStateFlow<List<String>>(emptyList())
    val outgoingDisplayNames: StateFlow<List<String>> = _outgoingDisplayNames.asStateFlow()

    private val _activeConnection = MutableStateFlow<Connection?>(null)
    val activeConnection: StateFlow<Connection?> = _activeConnection.asStateFlow()

    val sendUiState: StateFlow<SendUiState> by lazy {
        combine(
            _discoveredDevices,
            _isScanning,
            _activeConnection,
            _outgoingUris,
            _outgoingDisplayNames,
        ) { devices, scanning, active, uris, names ->
            SendUiState(
                discoveredDevices = devices,
                isScanning = scanning,
                selectedDevice = null,
                outgoingUris = uris,
                outgoingDisplayNames = names,
                activeConnection = active,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SendUiState())
    }

    private var scanNsdJob: Job? = null
    private var scanBleJob: Job? = null

    override fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        _discoveredDevices.value = emptyList()
        nsd.clearDiscoveredDevices()
        scanNsdJob = viewModelScope.launch {
            try {
                // Browse _up._tcp for WiFi-LAN TCP endpoints (host/port + ServiceId).
                nsd.discover().collect { dev ->
                    val current = _discoveredDevices.value
                    val merged = mergeDevice(current, dev)
                    _discoveredDevices.value = merged
                }
            } catch (e: Exception) {
                Log.w(TAG, "NSD discover error", e)
            }
        }
        scanBleJob = viewModelScope.launch {
            try {
                // Scan GATT 0xFCF1 and parse presence advert for the display name.
                ble.scan().collect { dev ->
                    val current = _discoveredDevices.value
                    val merged = mergeDevice(current, dev)
                    _discoveredDevices.value = merged
                }
            } catch (e: Exception) {
                Log.w(TAG, "BLE scan error", e)
            }
        }
    }

    private fun mergeDevice(current: List<NearbyDevice>, incoming: NearbyDevice): List<NearbyDevice> {
        val idx = current.indexOfFirst { it.endpointId == incoming.endpointId }
        return if (idx >= 0) {
            val existing = current[idx]
            // BLE presence name takes precedence over the raw ServiceId hex from _up._tcp.
            val bestName = when {
                incoming.source == DiscoverySource.Ble && incoming.endpointName.isNotBlank() -> incoming.endpointName
                existing.source == DiscoverySource.Ble && existing.endpointName.isNotBlank() -> existing.endpointName
                else -> incoming.endpointName.ifBlank { existing.endpointName }
            }
            val merged = existing.copy(
                endpointName = bestName,
                serviceId = incoming.serviceId ?: existing.serviceId,
                host = incoming.host ?: existing.host,
                port = incoming.port ?: existing.port,
                source = if (incoming.source != existing.source) DiscoverySource.Both else existing.source,
                serviceName = incoming.serviceName ?: existing.serviceName,
                extra = incoming.extra ?: existing.extra,
            )
            current.toMutableList().also { it[idx] = merged }
        } else {
            current + incoming
        }
    }

    override fun stopScan() {
        scanNsdJob?.cancel()
        scanBleJob?.cancel()
        scanNsdJob = null
        scanBleJob = null
        nsd.stopDiscovery()
        ble.stopScan()
        _isScanning.value = false
    }

    override fun connectToDevice(device: NearbyDevice) {
        val host = device.host
        val port = device.port
        if (host == null || port == null) {
            Log.w(TAG, "cannot connect to ${device.endpointName}: no host/port (requires _up._tcp browse)")
            return
        }
        viewModelScope.launch {
            try {
                val conn = transport.connect(host, port)
                _activeConnection.value = conn
                ShareTransferService.startSendMode(appContext, host, port)
                val uris = _outgoingUris.value
                if (uris.isNotEmpty()) {
                    sendUrisOver(conn, uris)
                }
            } catch (e: Exception) {
                Log.w(TAG, "connect to ${device.endpointName} failed", e)
            }
        }
    }

    override fun setOutgoingUris(uris: List<Uri>) {
        _outgoingUris.value = uris
        viewModelScope.launch(Dispatchers.IO) {
            val names = uris.map { uri -> resolveDisplayName(appContext, uri) ?: uri.toString() }
            _outgoingDisplayNames.value = names
        }
        val conn = _activeConnection.value
        if (conn != null && uris.isNotEmpty()) {
            viewModelScope.launch { sendUrisOver(conn, uris) }
        }
    }

    /** Entry from MainActivity's share intent — handles ACTION_SEND / SEND_MULTIPLE. */
    fun handleShareIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (uri != null) setOutgoingUris(listOf(uri))
                else if (!text.isNullOrBlank()) stageTextAsFile(text)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) setOutgoingUris(uris)
            }
        }
    }

    private fun stageTextAsFile(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = File(appContext.cacheDir, "share_send/shared_text.txt")
                f.parentFile?.mkdirs()
                f.writeText(text)
                val uri = Uri.fromFile(f)
                withContext(Dispatchers.Main) { setOutgoingUris(listOf(uri)) }
            } catch (e: Exception) {
                Log.w(TAG, "stageTextAsFile failed", e)
            }
        }
    }

    private suspend fun sendUrisOver(conn: Connection, uris: List<Uri>) = withContext(Dispatchers.IO) {
        val files = uris.mapNotNull { uri -> uriToTempFile(appContext, uri) }
        if (files.isNotEmpty()) {
            transport.sendFiles(conn, files)
        }
    }

    override fun clearOutgoing() {
        _outgoingUris.value = emptyList()
        _outgoingDisplayNames.value = emptyList()
    }

    override fun disconnect(connection: Connection) {
        viewModelScope.launch {
            transport.disconnect(connection)
            if (_activeConnection.value === connection) _activeConnection.value = null
        }
    }

    override fun onCleared() {
        stopScan()
        try {
            nsd.release()
        } catch (_: Exception) {
        }
        try {
            ble.release()
        } catch (_: Exception) {
        }
        try {
            transport.release()
        } catch (_: Exception) {
        }
    }
}

class ShareViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ShareViewModel::class.java)) {
            "Unknown ViewModel class $modelClass"
        }
        return ShareViewModel(application) as T
    }
}

internal fun resolveDisplayName(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") return File(uri.path ?: "").name.takeIf { it.isNotBlank() }
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
}

internal fun uriToTempFile(context: Context, uri: Uri): File? {
    return try {
        val name = resolveDisplayName(context, uri) ?: "share_file_${System.currentTimeMillis()}"
        val safeName = name.replace("/", "_").replace("\\", "_")
        val dest = File(context.cacheDir, "share_send/$safeName")
        dest.parentFile?.mkdirs()
        if (uri.scheme == "file") {
            val src = File(uri.path ?: "")
            if (src.exists() && src.isFile) {
                src.copyTo(dest, overwrite = true)
                return dest
            }
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
        if (dest.exists() && dest.length() > 0) dest else null
    } catch (e: Exception) {
        Log.w(TAG, "uriToTempFile failed for $uri", e)
        null
    }
}
