package com.vayunmathur.share.platform

import android.content.Context
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

/**
 * A file that finished arriving and is staged in app-private storage.
 *
 * [uri] is a `FileProvider` content URI, so it can be handed to another app without a
 * storage permission. The file stays where it is until the user shares or saves it.
 */
data class ReceivedFile(
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val uri: Uri,
)

data class TransferProgress(
    val state: ShareState,
    val pendingFiles: List<PendingFile>,
    val receivedFiles: List<ReceivedFile>,
    val bytesSent: Long,
    val bytesReceived: Long,
    val error: String?,
)

fun Connection.toProgress(): TransferProgress = TransferProgress(
    state = state.value,
    pendingFiles = pendingFiles.value,
    receivedFiles = receivedFiles.value,
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

    /**
     * Hand [file] to the system share chooser.
     *
     * Takes a [Context] because launching an Activity needs one that belongs to a task;
     * pass the composable's `LocalContext`, not an application context.
     */
    fun shareReceivedFile(context: Context, file: ReceivedFile)

    /** Copy [file] into [treeUri], a directory the user picked with `OpenDocumentTree`. */
    fun saveReceivedFile(file: ReceivedFile, treeUri: Uri)

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
            override fun shareReceivedFile(context: Context, file: ReceivedFile) {}
            override fun saveReceivedFile(file: ReceivedFile, treeUri: Uri) {}
        }
    }
}
