package com.vayunmathur.share.platform

import android.net.Uri
import com.vayunmathur.share.platform.discovery.NearbyDevice
import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.network.transport.Connection

/**
 * UI contract for the Share app (Receive + Send flows).
 *
 * Mirrors the per-repo pattern (see FindFamilyUiContract / MapsUiContract):
 * data-class UiState + actions interface with a Noop object for @Preview.
 */

data class ReceiveUiState(
    val isVisible: Boolean = false,
    val localName: String = "",
    val listenPort: Int? = null,
    val incomingConnections: List<Connection> = emptyList(),
    val hasPermissions: Boolean = true,
)

data class SendUiState(
    val discoveredDevices: List<NearbyDevice> = emptyList(),
    val isScanning: Boolean = false,
    val selectedDevice: NearbyDevice? = null,
    val outgoingUris: List<Uri> = emptyList(),
    val outgoingDisplayNames: List<String> = emptyList(),
    val activeConnection: Connection? = null,
    val hasPermissions: Boolean = true,
)

data class TransferProgress(
    val state: ShareState,
    val pendingFiles: List<PendingFile>,
    val bytesSent: Long,
    val bytesReceived: Long,
    val error: String?,
)

fun Connection.toProgress(): TransferProgress = TransferProgress(
    state = state.value,
    pendingFiles = pendingFiles.value,
    bytesSent = bytesSent.value,
    bytesReceived = bytesReceived.value,
    error = error.value,
)

interface ShareActions {
    fun setVisible(visible: Boolean)
    fun acceptIncoming(connection: Connection, accept: Boolean)
    fun startScan()
    fun stopScan()
    fun connectToDevice(device: NearbyDevice)
    fun setOutgoingUris(uris: List<Uri>)
    fun clearOutgoing()
    fun disconnect(connection: Connection)

    companion object {
        val Noop = object : ShareActions {
            override fun setVisible(visible: Boolean) {}
            override fun acceptIncoming(connection: Connection, accept: Boolean) {}
            override fun startScan() {}
            override fun stopScan() {}
            override fun connectToDevice(device: NearbyDevice) {}
            override fun setOutgoingUris(uris: List<Uri>) {}
            override fun clearOutgoing() {}
            override fun disconnect(connection: Connection) {}
        }
    }
}
