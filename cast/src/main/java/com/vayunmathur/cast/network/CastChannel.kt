package com.vayunmathur.cast.network

import android.annotation.SuppressLint
import android.util.Log
import com.vayunmathur.cast.domain.CAST_PORT
import com.vayunmathur.cast.domain.CastFrame
import com.vayunmathur.cast.domain.SENDER_ID
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "CastChannel"

private const val CONNECT_TIMEOUT_MS = 8_000

/**
 * How long a read may block before the link is treated as dead.
 *
 * Comfortably longer than the heartbeat interval, so a healthy channel never trips it: the
 * receiver answers every PING, so silence for this long means the link is gone even though
 * TCP has not noticed - which is what happens when Wi-Fi drops rather than being closed.
 */
private const val READ_TIMEOUT_MS = 30_000

/**
 * A frame larger than this is refused rather than allocated.
 *
 * RECEIVER_STATUS on a device with many namespaces is a few kilobytes, so this is orders of
 * magnitude of headroom; the point is only that a corrupt length prefix cannot ask for a
 * gigabyte.
 */
private const val MAX_FRAME_BYTES = 1 shl 20

/**
 * The TLS socket to a receiver, and the length-prefix framing on it.
 *
 * Transport only: it knows about `int32be(len) ‖ CastMessage` and nothing about namespaces,
 * request ids or sequencing - that all lives in `CastSession`, which is pure. The keepalive
 * timer lives with the session too, since PING is a session frame like any other; this class
 * just carries whatever it is handed.
 */
class CastChannel(private val host: String, private val port: Int = CAST_PORT) {

    private var socket: SSLSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    /** One writer at a time: a half-written frame desynchronises the stream permanently. */
    private val writeMutex = Mutex()

    suspend fun connect() = withContext(Dispatchers.IO) {
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            // autoClose = true, so the TLS socket adopts `plain` from here on - but only once
            // createSocket has returned, which is why the handshake is inside this try.
            val tls = permissiveSocketFactory().createSocket(plain, host, port, true) as SSLSocket
            tls.soTimeout = READ_TIMEOUT_MS
            tls.startHandshake()
            socket = tls
            input = DataInputStream(tls.inputStream.buffered())
            output = DataOutputStream(tls.outputStream.buffered())
            Log.i(TAG, "connected to $host:$port (${tls.session.protocol})")
        } catch (e: Exception) {
            // Nothing else holds `plain` yet, so a failure here would leak the descriptor.
            runCatching { plain.close() }
            throw e
        }
    }

    /**
     * Every frame the receiver sends, until the channel closes.
     *
     * Completes normally on a clean close and throws on a broken link, so the collector's
     * `catch` is the one place a dropped connection has to be handled.
     */
    fun messages(): Flow<CastMessage> = flow {
        while (true) {
            val message = readFrame() ?: break
            emit(message)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun send(frame: CastFrame) = withContext(Dispatchers.IO) {
        val message = CastMessage(
            sourceId = SENDER_ID,
            destinationId = frame.destinationId,
            namespace = frame.namespace,
            payloadUtf8 = frame.payload,
        )
        val bytes = CastMessageCodec.encode(message)
        writeMutex.withLock {
            val stream = output ?: error("send before connect")
            stream.writeInt(bytes.size)
            stream.write(bytes)
            stream.flush()
        }
    }

    fun close() {
        // Closing the socket is what unblocks a reader parked in readFrame; the streams are
        // dropped afterwards so a late send fails fast instead of writing into a dead socket.
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    /** Returns null at end of stream. Throws when the link breaks or a frame is malformed. */
    private fun readFrame(): CastMessage? {
        while (true) {
            val stream = input ?: return null
            val length = try {
                stream.readInt()
            } catch (_: EOFException) {
                return null
            }
            if (length <= 0 || length > MAX_FRAME_BYTES) {
                error("refusing a $length byte frame from $host")
            }
            val bytes = ByteArray(length)
            stream.readFully(bytes)
            // A frame that will not decode is dropped rather than fatal: the stream is still
            // in sync, because the length prefix said exactly how much to skip.
            val message = CastMessageCodec.decode(bytes)
            if (message != null) return message
            Log.w(TAG, "dropping a $length byte frame that is not a CastMessage")
        }
    }

    /**
     * TLS with verification switched off, for this socket and nothing else.
     *
     * CastV2 has no PKI: receivers present a self-signed device certificate, there is no CA
     * to chain it to and no name to match it against, so `pychromecast`, `node-castv2` and
     * Chrome's own implementation all skip verification. Doing the same is the only way to
     * speak the protocol at all.
     *
     * Deliberately a private [SSLContext] rather than
     * `HttpsURLConnection.setDefaultSSLSocketFactory` or anything touching
     * `library:network`'s `TrustBundle`: the permissiveness must not be reachable from any
     * other connection in the app. What is on this channel is a device name, a media URL and
     * transport controls, on the local network, with no credential of any kind - so the
     * attack it does not defend against (a LAN attacker impersonating the TV) costs the user
     * a video playing on the wrong screen.
     */
    @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
    private fun permissiveSocketFactory(): SSLSocketFactory {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }.socketFactory
    }
}
