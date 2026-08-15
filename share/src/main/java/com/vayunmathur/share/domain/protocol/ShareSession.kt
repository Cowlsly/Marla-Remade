package com.vayunmathur.share.domain.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PendingFile(val name: String, val sizeBytes: Long, val mimeType: String)

/** Transfer state — ordinal matches the Rust State enum (see PROTOCOL_CONTRACT.md §4). */
enum class ShareState(val code: Int) {
    Handshaking(0),
    AwaitingAccept(1),
    Transferring(2),
    Completed(3),
    Failed(4),
    Unknown(-1);

    companion object {
        fun fromCode(code: Int): ShareState = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/**
 * Thin lifecycle wrapper around the native session handle. Dispatch on
 * [state] in your UI (Handshaking → AwaitingAccept → Transferring → Completed/Failed).
 *
 * Usage (one instance per TCP connection / peer):
 * ```
 * val session = ShareSession(localName = "My Phone")
 * try {
 *     // after TCP connect, pump bytes:
 *     session.feedInbound(socket.read())
 *     session.drainOutbound()?.let { socket.write(it) }
 *     when (session.state) {
 *         AwaitingAccept -> showIncoming(session.pendingFiles)
 *         // on user tap:
 *         // session.accept(true, destDir); session.openFile(...); ...
 *         else -> {}
 *     }
 * } finally { session.destroy() }
 * ```
 */
class ShareSession @JvmOverloads constructor(
    localName: String,
    localEndpointInfo: ByteArray = ByteArray(0),
) : AutoCloseable {
    val handle: Long = ShareNative.nativeInit(localName, localEndpointInfo)
    private var closed = false

    /** Feed raw bytes read from the TCP socket into the protocol state machine. Returns 0 on ok. */
    fun feedInbound(bytes: ByteArray): Int = ShareNative.nativeFeedInbound(handle, bytes)

    /** Drain bytes Rust wants written to the socket; null if nothing to send. Call in a loop. */
    fun drainOutbound(): ByteArray? = ShareNative.nativeDrainOutbound(handle)

    /** Current state ordinal -> [ShareState]. Poll after feed/drain. */
    val state: ShareState get() = ShareState.fromCode(ShareNative.nativeQueryState(handle))

    /** Files announced by the sender's Introduction (valid in AwaitingAccept/Transferring). */
    val pendingFiles: List<PendingFile>
        get() {
            val raw = ShareNative.nativeQueryPendingFiles(handle) ?: return emptyList()
            if (raw.isEmpty()) return emptyList()
            return runCatching {
                Json.decodeFromString<List<PendingFile>>(raw.decodeToString())
            }.getOrElse { emptyList() }
        }

    /** Accept or reject the incoming transfer. Only valid in AwaitingAccept. */
    fun accept(accept: Boolean, destDir: String = ""): Int =
        ShareNative.nativeAccept(handle, accept, destDir)

    fun openFile(fileName: String, fileSize: Long): Int =
        ShareNative.nativeOpenFile(handle, fileName, fileSize)

    fun writeChunk(chunk: ByteArray): Int = ShareNative.nativeWriteChunk(handle, chunk)

    fun closeFile(): Int = ShareNative.nativeCloseFile(handle)

    fun destroy() {
        if (!closed) {
            closed = true
            ShareNative.nativeDestroy(handle)
        }
    }

    override fun close() = destroy()
}
