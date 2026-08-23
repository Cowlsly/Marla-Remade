package com.vayunmathur.cast.platform

import android.util.Log
import com.vayunmathur.cast.protocol.EphemeralTls
import com.vayunmathur.cast.protocol.ExchangeOutcome
import com.vayunmathur.cast.protocol.MediaProxyExchange
import com.vayunmathur.cast.protocol.MediaResourceResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

private const val TAG = "MediaProxyServer"

/**
 * How long a connection may sit idle between requests before it is reaped.
 *
 * Keep-alive is worth having - scrubbing is a burst of range requests - but a connection nobody
 * is using is a thread and a descriptor, and the TV opens a fresh one whenever it needs to.
 */
private const val IDLE_TIMEOUT_MS = 30_000

/**
 * More connections than any session needs, and few enough that a hostile peer on the LAN cannot
 * exhaust the process by opening sockets.
 *
 * A worst case is video, audio, a caption track and artwork at once, so this is generous.
 */
private const val MAX_CONNECTIONS = 16

/**
 * Serves app media to the TV over HTTPS, so the phone does not have to re-encode it.
 *
 * This is the whole reason the pixel path can go away for app content. The TV asks for byte
 * ranges of the original file and plays them with its own decoder, clock and buffer, which means
 * seeking is an offset rather than a key-frame renegotiation, pausing is the TV's business
 * entirely, and the phone neither decodes nor encodes a frame.
 *
 * The server belongs in `:cast` and not in the app being cast. `:sdk:cast` exists so that a
 * consumer owns no sockets and needs no network permission - `:music` has no `INTERNET`
 * permission at all - so the app supplies bytes through a [MediaResourceResolver] and this end
 * puts them on the wire.
 *
 * Everything that decides *what* to answer lives in `:cast:protocol`
 * ([MediaProxyExchange], [EphemeralTls]), where it can be tested without a TV. What is left here
 * is sockets, threads and lifecycle.
 */
class MediaProxyServer(
    private val token: String,
    private val resolver: MediaResourceResolver,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exchange = MediaProxyExchange(token, resolver)
    private val connections = AtomicInteger(0)

    private var server: SSLServerSocket? = null

    /** What the TV has to be told to be able to fetch anything. */
    data class Endpoint(val port: Int, val certificateFingerprint: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Endpoint &&
            port == other.port && certificateFingerprint.contentEquals(other.certificateFingerprint)

        override fun hashCode(): Int = 31 * port + certificateFingerprint.contentHashCode()
    }

    /**
     * Binds an ephemeral port and starts accepting.
     *
     * [addresses] end up as subject alternative names in the certificate, so they have to be the
     * addresses the TV will actually dial. Returns null when the port cannot be bound, which the
     * caller turns into a refused session rather than a session that silently plays nothing.
     */
    fun start(addresses: List<InetAddress> = localAddresses()): Endpoint? {
        if (server != null) return null
        return try {
            val credentials = EphemeralTls.server(addresses)
            val socket = credentials.sslContext.serverSocketFactory
                .createServerSocket(0, MAX_CONNECTIONS, InetAddress.getByName("0.0.0.0")) as SSLServerSocket
            restrictProtocols(socket.supportedProtocols) { socket.enabledProtocols = it }
            server = socket
            Log.i(TAG, "media proxy listening on ${socket.localPort} for ${addresses.joinToString { it.hostAddress ?: "?" }}")
            accept(socket)
            Endpoint(socket.localPort, credentials.fingerprint)
        } catch (e: Exception) {
            Log.w(TAG, "could not start the media proxy", e)
            stop()
            null
        }
    }

    fun stop() {
        // Closing the server socket is what unblocks the accept loop; cancelling first would
        // leave it parked in a blocking accept until a connection happened to arrive.
        runCatching { server?.close() }
        server = null
        scope.cancel()
    }

    private fun accept(socket: SSLServerSocket) {
        scope.launch {
            while (isActive) {
                val client = try {
                    socket.accept() as SSLSocket
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "accept failed", e)
                    break
                }
                if (connections.get() >= MAX_CONNECTIONS) {
                    Log.w(TAG, "refusing a connection: already serving ${connections.get()}")
                    runCatching { client.close() }
                    continue
                }
                connections.incrementAndGet()
                scope.launch { serve(client) }
            }
        }
    }

    private fun serve(client: SSLSocket) {
        try {
            client.soTimeout = IDLE_TIMEOUT_MS
            // Nagle would hold back the tail of a response waiting for more to send, which on a
            // small range request is pure added latency.
            client.tcpNoDelay = true
            val input = client.inputStream.buffered()
            val output = client.outputStream.buffered()
            while (true) {
                val result = exchange.serve(input, output)
                report(result.outcome, client)
                if (!result.reusable) break
            }
        } catch (e: Exception) {
            // A player that has finished with a range simply closes, so this is the ordinary end
            // of a connection as often as it is a fault.
            Log.d(TAG, "connection from ${client.inetAddress?.hostAddress} ended: ${e.javaClass.simpleName}")
        } finally {
            runCatching { client.close() }
            connections.decrementAndGet()
        }
    }

    private fun report(outcome: ExchangeOutcome, client: SSLSocket) {
        val peer = client.inetAddress?.hostAddress
        when (outcome) {
            is ExchangeOutcome.Served ->
                Log.d(TAG, "served ${outcome.resourceId} ${outcome.bytes} bytes to $peer")
            is ExchangeOutcome.Rejected ->
                // Logged as a warning because on a pinned, tokenised connection there is no
                // legitimate source of a rejected request: it is either a bug at our end or a
                // peer that should not be talking to us.
                Log.w(TAG, "refused $peer with ${outcome.status}: ${outcome.detail}")
            is ExchangeOutcome.Truncated ->
                Log.w(
                    TAG,
                    "${outcome.resourceId} gave ${outcome.actual} of ${outcome.expected} bytes; " +
                        "closing so the player sees a short body rather than a stall",
                )
            ExchangeOutcome.Closed -> Unit
        }
    }

    companion object {

        /**
         * A capability token for one session.
         *
         * URL-safe because it becomes a path segment, and unpadded so it needs no escaping. 32
         * bytes rather than something shorter because the token is the only thing between a LAN
         * peer that has guessed the port and the user's media.
         */
        fun randomToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** Every non-loopback address the TV might reach us on. */
        fun localAddresses(): List<InetAddress> =
            runCatching {
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.asSequence() }
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .toList()
            }.getOrDefault(emptyList())

        /**
         * TLS 1.2 at the oldest.
         *
         * Both ends are ours, so there is no legacy peer to accommodate, and the older protocols
         * are enabled by default on some platform versions.
         */
        private inline fun restrictProtocols(supported: Array<String>, apply: (Array<String>) -> Unit) {
            val wanted = supported.filter { it == "TLSv1.2" || it == "TLSv1.3" }
            if (wanted.isNotEmpty()) apply(wanted.toTypedArray())
        }
    }
}
