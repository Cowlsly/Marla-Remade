package com.vayunmathur.cast.platform

import com.vayunmathur.cast.protocol.EphemeralTls
import com.vayunmathur.cast.protocol.MediaProxy
import com.vayunmathur.cast.protocol.MediaResource
import java.io.InputStream
import java.net.InetAddress
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That stopping the proxy actually lets go of its sockets.
 *
 * Everything about *what* the proxy answers is `:cast:protocol`'s `MediaProxyTest`; what is left in
 * this class is sockets and lifecycle, and the one fault there is invisible from outside. An
 * unknown-length body is a single unbounded `write` for the whole track, so once the reader stops
 * the serving thread parks in TCP backpressure - and coroutine cancellation cannot interrupt a
 * thread blocked in socket I/O. A `stop()` that closed only the listening socket therefore leaked a
 * thread and a descriptor per session, and `connections` never came back down: after enough
 * reconnects `MAX_CONNECTIONS` refused to serve anything at all.
 */
class MediaProxyServerTest {

    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    @Test
    fun `stop unwinds a connection parked in an unbounded write`() {
        val token = MediaProxyServer.randomToken()
        val server = MediaProxyServer(token) { Endless() }
        val endpoint = assertNotNull(server.start(listOf(loopback)), "the proxy did not bind")
        try {
            val client = EphemeralTls.client(endpoint.certificateFingerprint)
                .socketFactory.createSocket(loopback, endpoint.port) as SSLSocket
            client.use {
                it.soTimeout = AWAIT_MS.toInt()
                it.startHandshake()
                it.outputStream.write(
                    "GET /$token/anything HTTP/1.1\r\nHost: ${loopback.hostAddress}\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1),
                )
                it.outputStream.flush()
                // Enough of the answer to know a body has started, and then deliberately nothing
                // more: an unread socket is what fills the window and parks the writer.
                assertTrue(it.inputStream.read() >= 0, "the proxy answered nothing")
                awaitConnections(server, 1)
                Thread.sleep(WRITE_BLOCK_MS)
                // The resource never ends, so a connection still counted here is one whose thread
                // is inside `write` rather than one that finished.
                assertEquals(1, server.openConnections, "the transfer ended by itself")

                server.stop()
                awaitConnections(server, 0)
            }
        } finally {
            server.stop()
        }
    }

    private fun awaitConnections(server: MediaProxyServer, expected: Int) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (server.openConnections == expected) return
            Thread.sleep(POLL_MS)
        }
        assertEquals(expected, server.openConnections, "the connection count never settled")
    }

    /** A resource that is always ready with more bytes and never ends, like a live transcode. */
    private class Endless : MediaResource {
        override val length: Long = MediaProxy.UNKNOWN_LENGTH
        override val contentType: String = "audio/ogg"

        override fun open(offset: Long): InputStream = object : InputStream() {
            override fun read(): Int = 0

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(0, off, off + len)
                return len
            }
        }
    }

    private companion object {
        const val AWAIT_MS = 5_000L
        const val POLL_MS = 20L

        /** Long enough for the socket buffers to fill and the serving thread to stop making progress. */
        const val WRITE_BLOCK_MS = 500L
    }
}
