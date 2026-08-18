package com.vayunmathur.share.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.share.BuildConfig
import com.vayunmathur.share.R
import com.vayunmathur.share.platform.discovery.BleDiscoveryManager
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import com.vayunmathur.share.platform.discovery.NsdDiscoveryManager
import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.transfer.ShareTransferService
import com.vayunmathur.share.protocol.ShareNative
import com.vayunmathur.share.network.transport.Connection
import com.vayunmathur.share.network.transport.TcpTransport
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
 * Everyone-mode only (no Google account, so no contact certificates):
 *  - Visibility: `ServerSocket(0)` on WIFI_LAN, its port registered under
 *    `_FC9F5ED42C8A._tcp` (the service type derived from `SHA-256("NearbySharing")`),
 *    plus a Nearby Connections `BleAdvertisement` under GATT `0xFEF3`.
 *  - Discovery: browse `_FC9F5ED42C8A._tcp` (resolves host/port) and scan `0xFEF3`, then
 *    merge by endpointId. Connecting needs host/port, which only the mDNS leg supplies.
 *
 * One endpoint id and one endpoint-info blob are shared by the BLE advertisement, the mDNS
 * record and `CONNECTION_REQUEST`. A peer matches the request against what it discovered,
 * so a device that advertises one identity and dials out under another is hung up on
 * (`p000\each.java:2092-2097`).
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
        val trimmed = name.trim().ifBlank { return }
        if (trimmed == _localName.value) return
        _localName.value = trimmed
        // The name is inside the endpoint-info blob, so renaming means re-advertising.
        if (_isVisible.value) setVisible(true)
    }

    // --- Subsystems (lazy so the VM can be constructed in previews / tests
    // without touching the framework) ------------------------------------

    private val nsd by lazy { NsdDiscoveryManager(appContext) }
    private val ble by lazy { BleDiscoveryManager(appContext) }
    private val receivedStore by lazy { ReceivedFileStore(appContext) }
    // TcpTransport owns ServerSocket(0) on WIFI_LAN; NsdDiscoveryManager publishes its port.
    private val transport by lazy {
        TcpTransport(
            localName = _localName.value,
            receivedStore = receivedStore,
            localEndpointInfo = endpointInfo() ?: ByteArray(0),
            localEndpointId = localEndpointId,
        )
    }

    /**
     * Endpoint id advertised as the mDNS instance name. Nearby endpoint ids are four
     * characters; a stable random one per process keeps re-advertising idempotent.
     */
    private val localEndpointId: String by lazy {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        (1..4).map { alphabet.random() }.joinToString("")
    }

    /**
     * The endpoint-info blob for the current device name, or null if it cannot be built.
     *
     * PHONE is cosmetic — it picks the icon the peer renders next to our name.
     */
    private fun endpointInfo(): ByteArray? = try {
        ShareNative.nativeBuildEndpointInfo(_localName.value, ShareNative.DEVICE_TYPE_PHONE)
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "libshare_nearby unavailable — cannot build endpoint info", e)
        null
    }

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
        if (visible) {
            val endpointInfo = endpointInfo()
            if (endpointInfo == null) {
                Log.w(TAG, "cannot advertise without an endpoint info blob")
                _isVisible.value = false
                return
            }
            _isVisible.value = true
            // Before listen(), so a socket accepted immediately announces the identity we
            // are about to advertise rather than the transport's construction-time one.
            transport.setLocalIdentity(localName.value, endpointInfo, localEndpointId)
            val port = transport.listen()
            // Publish the WIFI_LAN port under _FC9F5ED42C8A._tcp, with the endpoint info in
            // the `n` TXT attribute — the record a Quick Share device lists us from.
            nsd.advertise(localEndpointId, endpointInfo, port)
            // The same blob inside a Nearby Connections BleAdvertisement under 0xFEF3.
            ble.startAdvertising(localEndpointId, endpointInfo)
            ShareTransferService.startReceiveMode(appContext, port)
        } else {
            _isVisible.value = false
            nsd.unadvertise()
            ble.stopAdvertising()
            transport.stopListening()
            ShareTransferService.stop(appContext)
        }
    }

    override fun acceptIncoming(connection: Connection, accept: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val rc = transport.acceptIncoming(connection, accept)
            if (rc < 0) {
                Log.w(TAG, "acceptIncoming failed rc=$rc")
            }
        }
    }

    override fun shareReceivedFile(context: Context, file: ReceivedFile) {
        ExternalIntents.shareFile(
            context = context,
            uri = file.uri,
            mimeType = file.mimeType,
            chooserTitle = context.getString(R.string.share_received_chooser),
        )
    }

    override fun saveReceivedFile(file: ReceivedFile, treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = copyIntoTree(file, treeUri)
            AppMessages.show(
                appContext.getString(
                    if (saved) R.string.share_save_succeeded else R.string.share_save_failed,
                    file.name,
                )
            )
        }
    }

    /**
     * Copy [file] into the SAF tree [treeUri] the user picked.
     *
     * Uses the platform [DocumentsContract] rather than `androidx.documentfile`, which
     * is not a dependency of this repo. `createDocument` may hand back a different
     * display name than requested if one is taken; that is the provider's call, not
     * ours, so nothing here second-guesses it.
     */
    private fun copyIntoTree(file: ReceivedFile, treeUri: Uri): Boolean = try {
        val resolver = appContext.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val target = DocumentsContract.createDocument(resolver, parent, file.mimeType, file.name)
        if (target == null) {
            false
        } else {
            resolver.openInputStream(file.uri)?.use { input ->
                resolver.openOutputStream(target)?.use { output -> input.copyTo(output) }
            } != null
        }
    } catch (e: Exception) {
        Log.w(TAG, "saveReceivedFile failed for ${file.name}", e)
        false
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
                // Browse _FC9F5ED42C8A._tcp for WIFI_LAN endpoints (host/port + name).
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
                // Scan GATT 0xFEF3 for Nearby Connections BleAdvertisements.
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
            // Both legs now carry the peer's advertised name, but the mDNS record is the
            // one that also supplies host/port, so prefer it and treat BLE as a tiebreak.
            val bestName = when {
                incoming.source == DiscoverySource.Nsd && incoming.endpointName.isNotBlank() -> incoming.endpointName
                existing.source == DiscoverySource.Nsd && existing.endpointName.isNotBlank() -> existing.endpointName
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
            Log.w(TAG, "cannot connect to ${device.endpointName}: no host/port (needs the mDNS browse)")
            return
        }
        viewModelScope.launch {
            try {
                // A rename while hidden never re-advertised, so refresh the identity the
                // CONNECTION_REQUEST will carry before dialling.
                endpointInfo()?.let {
                    transport.setLocalIdentity(localName.value, it, localEndpointId)
                }
                val conn = transport.connect(host, port)
                _activeConnection.value = conn
                ShareTransferService.startSendMode(appContext, host, port)
                val uris = _outgoingUris.value.ifEmpty { devTestFileUri()?.let(::listOf).orEmpty() }
                if (uris.isNotEmpty()) {
                    sendUrisOver(conn, uris)
                }
            } catch (e: Exception) {
                Log.w(TAG, "connect to ${device.endpointName} failed", e)
            }
        }
    }

    /**
     * A throwaway file to send when nothing is selected, so a transfer can be exercised
     * without going through the file picker. Dev builds only.
     */
    private fun devTestFileUri(): Uri? {
        if (!BuildConfig.DEV_BUILD) return null
        return try {
            // Not under cache/share_send: that is where uriToTempFile copies *into*, and
            // copying a file onto itself deletes it.
            val f = File(appContext.cacheDir, "dev_test/share-test.txt")
            f.parentFile?.mkdirs()
            f.writeText(":share test payload ${System.currentTimeMillis()}\n")
            Log.i(TAG, "no files selected — sending ${f.name} (dev build only)")
            Uri.fromFile(f)
        } catch (e: Exception) {
            Log.w(TAG, "could not stage the dev test file", e)
            null
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
