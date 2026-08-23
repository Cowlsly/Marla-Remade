package com.vayunmathur.cast.network

import android.util.Log
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.StandardSocketOptions
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
            // An IDR at native resolution is a few hundred packets handed to the kernel at once,
            // and a non-blocking write into a full send buffer silently does not go out. Best
            // effort: the kernel clamps to its own maximum, so the granted size is read back and
            // logged.
            runCatching { setOption(StandardSocketOptions.SO_SNDBUF, SEND_BUFFER_BYTES) }
            connect(InetSocketAddress(host, port))
        }
        val granted = runCatching { channel?.getOption(StandardSocketOptions.SO_SNDBUF) }.getOrNull()
        Log.i(TAG, "udp connected to $host:$port with sndbuf=${granted}B")
        true
    } catch (e: Exception) {
        Log.w(TAG, "could not open a udp socket to $host:$port", e)
        close()
        false
    }

    /**
     * True once the receiver's port has answered ICMP "unreachable" repeatedly.
     *
     * On a connected datagram socket that surfaces as [PortUnreachableException] on *send*, which
     * means the receiver closed or never bound its port - so it is a diagnosis, not a transient. One
     * occurrence can happen before the receiver binds, hence the threshold.
     */
    val receiverGone: Boolean get() = unreachableCount >= UNREACHABLE_THRESHOLD

    private var unreachableCount = 0

    /**
     * Packets the kernel would not take, and the last time that was said out loud.
     *
     * This was the pipeline's largest diagnostic blind spot: a full send buffer made [send] return
     * false and the packet simply vanished - uncounted, unlogged, and recovered only if the receiver
     * happened to NACK it. Rate-limited to one line, because a burst that overruns the buffer
     * overruns it for hundreds of packets at a time.
     */
    var sendFailures: Long = 0
        private set

    private var lastFailureLogMs = 0L

    fun send(packet: ByteArray): Boolean {
        val active = channel ?: return false
        if (hexDump) Log.i(TAG, "-> ${packet.size}B ${packet.toHexPreview()}")
        return try {
            // A non-blocking write can accept fewer bytes than offered when the send buffer is
            // full, which for a datagram socket means the packet did not go. Reporting it as sent
            // would inflate the sender report and skew the receiver's loss estimate.
            val wrote = active.write(ByteBuffer.wrap(packet)) == packet.size
            unreachableCount = 0
            if (!wrote) countFailure()
            wrote
        } catch (e: PortUnreachableException) {
            // Counted rather than logged per packet: at 30 fps this would be thousands of identical
            // stack traces, which buries whatever else the log had to say.
            unreachableCount++
            if (unreachableCount == UNREACHABLE_THRESHOLD) {
                Log.w(TAG, "$host:$port is unreachable - the receiver closed its socket")
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "udp send failed", e)
            countFailure()
            false
        }
    }

    private fun countFailure() {
        sendFailures++
        val now = System.currentTimeMillis()
        if (now - lastFailureLogMs < FAILURE_LOG_INTERVAL_MS) return
        lastFailureLogMs = now
        Log.w(TAG, "the send buffer is full; $sendFailures packets dropped before they left")
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

        /** Room for one native-resolution IDR burst, so a frame is not lost to its own size. */
        const val SEND_BUFFER_BYTES = 2 * 1024 * 1024

        /** One unreachable reply can precede the receiver binding; a run of them cannot. */
        const val UNREACHABLE_THRESHOLD = 30

        /** Send failures come in bursts, so they are summarised rather than reported. */
        const val FAILURE_LOG_INTERVAL_MS = 1_000L
    }
}
