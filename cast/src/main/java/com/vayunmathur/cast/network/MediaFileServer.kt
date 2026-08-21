package com.vayunmathur.cast.network

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "MediaFileServer"

private const val COPY_BUFFER_BYTES = 64 * 1024

/**
 * The one-file HTTP server a local cast needs.
 *
 * A Chromecast plays a URL, not a file, so casting something the user picked means serving it
 * over the LAN for the duration of the session. There is no embedded HTTP server anywhere
 * else in this repo, so this is deliberately the smallest thing that works with a real
 * receiver:
 *
 *  - **One URI at a time**, at a random 128-bit path token. The server is reachable by
 *    anything on the LAN, so the token is what stops it being a browsable window onto
 *    whatever the app can read; nothing else is served, at any path.
 *  - **`GET` and `HEAD` only.** Chromecast probes with `HEAD` before it plays.
 *  - **`Range` with `206 Partial Content`.** Seeking is implemented client-side by asking for
 *    a byte offset, so without this the scrub bar silently does nothing. See [parseByteRange].
 *  - **Bound to the Wi-Fi address**, so the URL handed to the receiver is one it can reach,
 *    and torn down with the session.
 *
 * The file is read through `ContentResolver.openFileDescriptor`, which is why the app needs no
 * storage or media permission: a URI from `ACTION_OPEN_DOCUMENT` or the share sheet already
 * carries its own grant.
 */
class MediaFileServer(private val contentResolver: ContentResolver) {

    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null

    /**
     * Sockets with a response in flight.
     *
     * Closing the [ServerSocket] frees the port but does nothing to a `GET` already streaming,
     * and neither does cancelling the scope: the copy loop is blocking I/O. So [stop] closes
     * these too, which is what makes "the session ended" mean the file stops being readable.
     */
    private val openClients = Collections.synchronizedSet(mutableSetOf<Socket>())

    @Volatile
    private var served: Served? = null

    private class Served(
        val token: String,
        val uri: Uri,
        val mimeType: String,
        val length: Long,
    )

    /**
     * Start serving [uri] and return the URL to hand to the receiver, or null when the file
     * cannot be measured or there is no LAN address to bind to.
     *
     * Replaces whatever was being served: the previous URL stops working immediately, which is
     * correct - a session only ever plays one thing.
     */
    fun start(uri: Uri, mimeType: String): String? {
        stop()
        val length = fileLength(uri)
        if (length <= 0) {
            Log.w(TAG, "cannot determine the length of $uri - refusing to serve it")
            return null
        }
        val address = localAddress() ?: run {
            Log.w(TAG, "no usable local network address - cannot serve $uri")
            return null
        }
        val socket = try {
            ServerSocket(0, 8, address)
        } catch (e: Exception) {
            Log.w(TAG, "could not bind a server socket on ${address.hostAddress}", e)
            return null
        }
        val token = randomToken()
        served = Served(token, uri, mimeType, length)
        server = socket
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serverScope
        acceptJob = serverScope.launch {
            while (true) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    // Closed by stop(), or the interface went away. Either way we are done.
                    break
                }
                launch { handle(client) }
            }
        }
        return "http://${address.hostAddress}:${socket.localPort}/$token"
    }

    fun stop() {
        served = null
        runCatching { server?.close() }
        server = null
        acceptJob = null
        synchronized(openClients) {
            openClients.forEach { runCatching { it.close() } }
            openClients.clear()
        }
        scope?.cancel()
        scope = null
    }

    private fun handle(client: Socket) {
        openClients.add(client)
        try {
            client.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    respondStatus(output, 400, "Bad Request")
                    return
                }
                val method = parts[0].uppercase()
                val path = parts[1]
                val headers = readHeaders(input)
                val target = served
                if (target == null || path != "/${target.token}") {
                    respondStatus(output, 404, "Not Found")
                    return
                }
                if (method != "GET" && method != "HEAD") {
                    respondStatus(output, 405, "Method Not Allowed", extra = "Allow: GET, HEAD")
                    return
                }
                respondBody(output, method, headers["range"], target)
            }
        } catch (e: Exception) {
            // A receiver that abandons a request mid-body is normal - it does exactly that
            // when the user seeks - so a broken pipe here is not worth more than a debug line.
            Log.d(TAG, "request failed", e)
        } finally {
            openClients.remove(client)
        }
    }

    private fun respondBody(
        output: OutputStream,
        method: String,
        rangeHeader: String?,
        target: Served,
    ) {
        when (val range = parseByteRange(rangeHeader, target.length)) {
            is ByteRangeResult.Unsatisfiable -> respondStatus(
                output,
                416,
                "Range Not Satisfiable",
                extra = "Content-Range: bytes */${target.length}",
            )
            is ByteRangeResult.Whole -> {
                writeHeaders(
                    output,
                    status = "200 OK",
                    contentType = target.mimeType,
                    contentLength = target.length,
                )
                if (method == "GET") copy(target, ByteRange(0, target.length - 1), output)
            }
            is ByteRangeResult.Partial -> {
                writeHeaders(
                    output,
                    status = "206 Partial Content",
                    contentType = target.mimeType,
                    contentLength = range.range.length,
                    extra = "Content-Range: bytes ${range.range.first}-${range.range.last}/${target.length}",
                )
                if (method == "GET") copy(target, range.range, output)
            }
        }
        output.flush()
    }

    private fun copy(target: Served, range: ByteRange, output: OutputStream) {
        contentResolver.openFileDescriptor(target.uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                // position() rather than skip(): skip on a large offset is allowed to do less
                // than asked, which would silently serve the wrong bytes for a seek.
                stream.channel.position(range.first)
                var remaining = range.length
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (remaining > 0) {
                    val wanted = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = stream.read(buffer, 0, wanted)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun writeHeaders(
        output: OutputStream,
        status: String,
        contentType: String,
        contentLength: Long,
        extra: String? = null,
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(contentLength).append("\r\n")
            // Advertised unconditionally: a receiver that does not see this will not attempt a
            // Range request at all, and seeking would be unavailable even though it works.
            append("Accept-Ranges: bytes\r\n")
            if (extra != null) append(extra).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
    }

    private fun respondStatus(
        output: OutputStream,
        code: Int,
        reason: String,
        extra: String? = null,
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Length: 0\r\n")
            if (extra != null) append(extra).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** Header names are case-insensitive, so they are lowercased on the way in. */
    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] =
                line.substring(colon + 1).trim()
        }
        return headers
    }

    /**
     * One CRLF-terminated line, read a byte at a time.
     *
     * Not a `BufferedReader`: that decodes ahead into a character buffer, which would swallow
     * the start of the body on a request that had one.
     */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            when (val b = input.read()) {
                -1 -> return if (line.isEmpty()) null else line.toString()
                '\n'.code -> return line.toString().removeSuffix("\r")
                else -> {
                    if (line.length > 8192) return null
                    line.append(b.toChar())
                }
            }
        }
    }

    private fun fileLength(uri: Uri): Long =
        runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }.getOrDefault(-1L)

    /**
     * The address the receiver has to be able to reach us on.
     *
     * Enumerating interfaces rather than asking `ConnectivityManager`: this needs a bindable
     * `InetAddress`, and it must be the LAN one - a link-local or loopback address in the URL
     * produces a receiver that reports a load failure with no explanation.
     */
    private fun localAddress(): InetAddress? =
        runCatching {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull {
                    it.address.size == 4 && !it.isLoopbackAddress && !it.isLinkLocalAddress
                }
        }.getOrNull()

    private fun randomToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
