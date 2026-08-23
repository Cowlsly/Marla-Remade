package com.vayunmathur.cast.tv.platform

import android.util.Log
import com.vayunmathur.cast.protocol.DecodableFrame
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.ReceiverSession
import com.vayunmathur.cast.protocol.StreamKind
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

private const val TAG = "MediaReceiver"

/** Big enough for any RTP packet the sender emits, which is capped below the Ethernet MTU. */
private const val MAX_DATAGRAM = 2048

/**
 * The UDP half of the receiver: datagrams in, decoded frames out, RTCP back.
 *
 * The mirror of `:cast`'s `MirrorEngine`, and kept just as thin: [ReceiverSession] decides what is
 * decodable and what to ask for, [VideoDecoder] and [AudioPlayer] do the platform work, and this
 * class is the socket plus one loop. That split is why the receiver's rules are unit-tested and this
 * file is not.
 *
 * One socket for both streams and both directions, because that is what the sender expects: it
 * connects a single datagram socket to the port in `STREAM_READY` and sends everything on it, so
 * RTCP has to go back from the same port it arrived at or the sender's connected socket will not
 * accept it.
 */
class MediaReceiver(
    private val socket: DatagramSocket,
    negotiation: Negotiation,
    private val onVideo: (DecodableFrame) -> Unit,
    private val onAudio: (DecodableFrame) -> Unit,
    /** True while there is somewhere to draw. Video is dropped, not buffered, until there is. */
    private val videoReady: () -> Boolean,
) {

    private val sessions = negotiation.streams.associate { it.kind to ReceiverSession(it) }

    /** Where the sender's packets came from, which is where feedback goes back to. */
    private var senderAddress: InetAddress? = null
    private var senderPort: Int = 0

    val port: Int get() = socket.localPort

    init {
        // Bounded so the loop can check for a stop request rather than parking for ever, so a sender
        // that dies is noticed instead of leaving a thread blocked in recv, and so the caller gets
        // back often enough to service its playout deadlines.
        socket.soTimeout = RECEIVE_TIMEOUT_MS
    }

    /**
     * Block for one datagram and handle it.
     *
     * Returns false on a timeout, which tells the caller nothing arrived - it runs its own feedback
     * and playout timers either way, so a quiet socket is not a reason to stop looping.
     */
    fun pump(): Boolean {
        val buffer = ByteArray(MAX_DATAGRAM)
        val datagram = DatagramPacket(buffer, buffer.size)
        try {
            socket.receive(datagram)
        } catch (_: java.net.SocketTimeoutException) {
            return false
        } catch (e: Exception) {
            Log.w(TAG, "udp receive failed", e)
            return false
        }
        senderAddress = datagram.address
        senderPort = datagram.port
        val bytes = buffer.copyOf(datagram.length)

        // Routed by trying each stream: ReceiverSession itself checks the SSRC, so a datagram for the
        // other half of the mirror costs one rejected parse rather than a duplicate of that logic
        // here. Sender reports match neither and are handled after.
        for ((kind, session) in sessions) {
            // **Video is dropped before it reaches the session, not after.** The phone starts sending
            // the moment STREAM_READY goes out and the Activity takes a few hundred ms to produce a
            // surface, so the first key frame - the only one carrying SPS/PPS - usually arrives in that
            // window. Letting the session consume it would advance its checkpoint and mark it
            // synchronised, so no PLI would ever go out and the decoder would later be handed a bare
            // IDR: a black screen with nothing logged, which is exactly the failure this protocol
            // exists to make impossible. Dropping it here leaves the session unsynchronised, so its
            // next feedback asks for a key frame and the sender prepends the parameter sets again.
            if (kind == StreamKind.Video && !videoReady()) continue
            val frames = session.onPacket(bytes)
            if (frames.isEmpty()) continue
            for (frame in frames) {
                when (kind) {
                    StreamKind.Video -> onVideo(frame)
                    StreamKind.Audio -> onAudio(frame)
                }
            }
            return true
        }
        // Not RTP for either stream. A sender report is the expected case; anything else is somebody
        // else's traffic and is dropped without comment.
        for (session in sessions.values) session.onSenderReport(bytes)
        return true
    }

    /**
     * Report on every stream.
     *
     * Sent even before anything has been decoded - that first report is a PLI, and it is what tells
     * the sender to produce a key frame now rather than at its next scheduled one. Nothing goes out
     * before the first datagram arrives, because until then there is no address to send it to.
     *
     * [senderIdle] is true when the phone has told us playback is paused, and suppresses the key-frame
     * request - see [ReceiverSession.feedback] for why a paused cast otherwise never recovers.
     */
    fun sendFeedback(senderIdle: Boolean = false) {
        val address = senderAddress ?: return
        for (session in sessions.values) {
            val packet = session.feedback(senderIdle)
            try {
                socket.send(DatagramPacket(packet, packet.size, address, senderPort))
            } catch (e: Exception) {
                Log.w(TAG, "could not send feedback", e)
                return
            }
        }
    }

    /**
     * Give up on one stream's reference chain, so the next feedback asks for a key frame.
     *
     * The caller's decoder is the only thing that knows a frame this session counted as delivered was
     * refused, and a decoder missing a reference produces a smear rather than an error.
     */
    fun requestKeyFrame(kind: StreamKind) {
        sessions[kind]?.requestKeyFrame()
    }

    /**
     * The line that has been missing all project: the receiver saying what it sees.
     *
     * The sender already logs packets sent and feedback received once a second. Reading the two
     * together is the whole difference between "the TV is black" and knowing which end is unhappy.
     */
    fun throughputSummary(): String = sessions.entries.joinToString(" ") { (kind, session) ->
        "$kind=${session.packetsReceived}pkt/${session.framesDelivered}frames" +
            "/ignored=${session.packetsIgnored}/checkpoint=${session.checkpoint}"
    }

    fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        /**
         * Short, because the caller has playout deadlines to meet.
         *
         * It used to double as the feedback interval - a timeout was what triggered a report - and no
         * longer does: the caller keeps its own feedback timer, so this only has to be short enough
         * that a frame due for presentation is not held behind a socket read. 10 ms is a third of a
         * frame interval at 30 fps.
         */
        const val RECEIVE_TIMEOUT_MS = 10
    }
}
