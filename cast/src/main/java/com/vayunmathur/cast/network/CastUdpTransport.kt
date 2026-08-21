package com.vayunmathur.cast.network

import android.util.Log
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

private const val TAG = "CastUdpTransport"

/**
 * The UDP socket carrying RTP out and RTCP back.
 *
 * Same idiom as `vpn/.../VpnTunnelService.kt:227-236` - `DatagramChannel.open()`,
 * `configureBlocking(false)`, `connect()` - **minus `protect()`**, which is a `VpnService` method
 * and does not exist here. Connecting rather than sending to an address each time is what lets the
 * kernel filter inbound datagrams to the receiver, so RTCP from anything else never arrives.
 *
 * The receiver sends RTCP from the same port it receives RTP on, so one connected socket does both
 * directions.
 */
class CastUdpTransport(private val host: String, private val port: Int) {

    private var channel: DatagramChannel? = null
    private val readBuffer = ByteBuffer.allocateDirect(MAX_DATAGRAM)

    /** Set to log every packet as hex. Off by default: it is per-packet and very loud. */
    var hexDump: Boolean = false

    fun open(): Boolean = try {
        channel = DatagramChannel.open().apply {
            configureBlocking(false)
            connect(InetSocketAddress(host, port))
        }
        Log.i(TAG, "udp connected to $host:$port")
        true
    } catch (e: Exception) {
        Log.w(TAG, "could not open a udp socket to $host:$port", e)
        close()
        false
    }

    fun send(packet: ByteArray): Boolean {
        val active = channel ?: return false
        if (hexDump) Log.i(TAG, "-> ${packet.size}B ${packet.toHexPreview()}")
        return try {
            active.write(ByteBuffer.wrap(packet))
            true
        } catch (e: Exception) {
            Log.w(TAG, "udp send failed", e)
            false
        }
    }

    /** One datagram, or null when nothing is waiting. Never blocks. */
    fun receive(): ByteArray? {
        val active = channel ?: return null
        return try {
            readBuffer.clear()
            if (active.read(readBuffer) <= 0) return null
            readBuffer.flip()
            ByteArray(readBuffer.remaining()).also { readBuffer.get(it) }
                .also { if (hexDump) Log.i(TAG, "<- ${it.size}B ${it.toHexPreview()}") }
        } catch (e: Exception) {
            // A port-unreachable ICMP surfaces here on a connected socket. Not fatal: the receiver
            // may not have bound yet.
            null
        }
    }

    fun close() {
        runCatching { channel?.close() }
        channel = null
    }

    /** Enough to identify a header without filling logcat with a whole video frame. */
    private fun ByteArray.toHexPreview(): String =
        take(32).joinToString("") { "%02x".format(it) } + if (size > 32) "..." else ""

    private companion object {
        const val MAX_DATAGRAM = 2048
    }
}
