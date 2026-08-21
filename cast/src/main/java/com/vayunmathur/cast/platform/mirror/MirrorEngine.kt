package com.vayunmathur.cast.platform.mirror

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import com.vayunmathur.cast.domain.streaming.CastRtcp
import com.vayunmathur.cast.domain.streaming.NegotiatedStream
import com.vayunmathur.cast.domain.streaming.Negotiation
import com.vayunmathur.cast.domain.streaming.StreamKind
import com.vayunmathur.cast.domain.streaming.StreamSelection
import com.vayunmathur.cast.domain.streaming.StreamingSession
import com.vayunmathur.cast.network.CastUdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MirrorEngine"

/** How often a sender report goes out; roughly openscreen's cadence. */
private const val SENDER_REPORT_INTERVAL_MS = 500L

/** Seconds between the NTP epoch (1900) and the Unix epoch (1970). */
private const val NTP_UNIX_OFFSET_SECONDS = 2_208_988_800L

private const val FRAME_POLL_MS = 4L
private const val RTCP_POLL_MS = 20L
private const val STATS_LOG_INTERVAL_MS = 1_000L

/** What could not be started, so the UI can say so rather than looking broken. */
data class MirrorDegradation(
    val videoUnavailable: Boolean = false,
    val audioUnavailable: Boolean = false,
)

/** Why mirroring could not start, or stopped. */
enum class MirrorStopReason { Udp, NoEncoders, NoAudioForSpeaker, ReceiverGone }

/**
 * The running mirror: capture and encode in, RTP out, RTCP back.
 *
 * Holds no protocol knowledge - [StreamingSession] decides what to retransmit, [CastRtpPacketizer]
 * decides byte layout, [StreamSender] does the per-stream work. This class is the part that cannot
 * be unit-tested, so it is kept to the wiring and two loops.
 *
 * [receiverHost] is passed in because the ANSWER carries only a port; the address is the one the
 * control channel is already talking to.
 *
 * The context is reduced to the application context on the way in: this is reachable from
 * `CastController`, which is an object, so holding the Service that started mirroring would outlive
 * it. Only `WindowManager` metrics are read from it, which the application context serves fine.
 */
class MirrorEngine(
    context: Context,
    private val projection: MediaProjection,
    private val receiverHost: String,
    private val negotiation: Negotiation,
    private val geometry: CaptureGeometry,
    private val session: StreamingSession,
    private val onDegraded: (MirrorDegradation) -> Unit,
    private val onStopped: (MirrorStopReason) -> Unit,
) {

    private val appContext: Context = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Concurrent because the RTCP loop iterates it while [stop] clears it, and the encoder loops
     * populate it during [start].
     */
    private val senders = ConcurrentHashMap<StreamKind, StreamSender>()

    private var videoJob: Job? = null
    private var audioJob: Job? = null
    private var rtcpJob: Job? = null

    private var transport: CastUdpTransport? = null
    private var capture: ScreenCapture? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null

    /**
     * Hex-dump every packet, and log throughput once a second.
     *
     * Settable from [CastController] via a debug switch, because a pipeline that can only be
     * diagnosed by reading it is a pipeline that cannot be diagnosed.
     */
    var hexDump: Boolean = false

    /** Counts inbound RTCP so a run can be told apart from one where nothing came back. */
    private var feedbackPackets = 0L
    private var unmatchedPackets = 0L

    fun start(): Boolean {
        val udp = CastUdpTransport(receiverHost, negotiation.udpPort)
        udp.hexDump = hexDump
        if (!udp.open()) {
            onStopped(MirrorStopReason.Udp)
            return false
        }
        transport = udp

        val videoStream = negotiation.video
        val audioStream = negotiation.audio
        val videoUnavailable = videoStream != null && !startVideo(videoStream, udp)
        val audioUnavailable = audioStream != null && !startAudio(audioStream, udp)

        // Stated degradation policy: a TV that lost audio still mirrors, with a notice; a speaker
        // that lost audio has nothing left to send at all, so it refuses.
        if (audioUnavailable && !negotiation.hasVideo) {
            Log.w(TAG, "audio-only target but no audio pipeline")
            stop()
            onStopped(MirrorStopReason.NoAudioForSpeaker)
            return false
        }
        if (senders.isEmpty()) {
            stop()
            onStopped(MirrorStopReason.NoEncoders)
            return false
        }
        if (videoUnavailable || audioUnavailable) {
            onDegraded(MirrorDegradation(videoUnavailable, audioUnavailable))
        }
        startRtcp(udp)
        return true
    }

    private fun startVideo(stream: NegotiatedStream, udp: CastUdpTransport): Boolean {
        val screen = ScreenCapture(projection)
        val encoder = VideoEncoder(
            width = geometry.width,
            height = geometry.height,
            frameRate = StreamSelection.VIDEO_MAX_FRAME_RATE,
            bitRate = geometry.bitRate,
        )
        if (!encoder.start()) return false
        val surface = encoder.inputSurface
        if (surface == null || !screen.start(surface, geometry)) {
            encoder.release()
            screen.release()
            return false
        }
        Log.i(
            TAG,
            "mirroring at ${geometry.width}x${geometry.height} " +
                "@ ${geometry.bitRate / 1_000_000.0} Mbit/s",
        )
        videoEncoder = encoder
        capture = screen
        val sender = StreamSender(stream, udp, session)
        senders[StreamKind.Video] = sender
        videoJob = scope.launch {
            while (isActive) {
                val chunks = encoder.drain()
                if (chunks.isEmpty()) {
                    delay(FRAME_POLL_MS)
                    continue
                }
                for (chunk in chunks) sender.send(chunk)
            }
        }
        return true
    }

    private fun startAudio(stream: NegotiatedStream, udp: CastUdpTransport): Boolean {
        val encoder = AudioEncoder(projection)
        if (!encoder.start()) return false
        audioEncoder = encoder
        val sender = StreamSender(stream, udp, session)
        senders[StreamKind.Audio] = sender
        audioJob = scope.launch {
            while (isActive) {
                val chunks = encoder.pump()
                for (chunk in chunks) sender.send(chunk)
                if (chunks.isEmpty()) delay(FRAME_POLL_MS)
            }
        }
        return true
    }

    /**
     * Sender reports out, feedback in.
     *
     * One loop for both directions because they share one socket, and because a report is cheap
     * enough that polling for feedback at report cadence needs no second coroutine.
     */
    private fun startRtcp(udp: CastUdpTransport) {
        rtcpJob = scope.launch {
            var lastReport = 0L
            var lastStatsLog = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                if (now - lastReport >= SENDER_REPORT_INTERVAL_MS) {
                    lastReport = now
                    for ((kind, sender) in senders) {
                        val stream = negotiation.streams.firstOrNull { it.kind == kind } ?: continue
                        val stats = sender.stats
                        // Nothing has been sent yet, so there is no clock mapping to report.
                        if (stats.lastSentAtMillis == 0L) continue
                        udp.send(
                            CastRtcp.senderReport(
                                senderSsrc = stream.senderSsrc,
                                // Paired with the RTP timestamp captured at the same instant, not
                                // with the current clock.
                                ntpTimestamp = ntpTimestamp(stats.lastSentAtMillis),
                                rtpTimestamp = stats.lastRtpTimestamp,
                                packetCount = stats.packets,
                                octetCount = stats.octets,
                            ),
                        )
                    }
                }
                // The single most useful line for diagnosing "the TV is black": whether packets are
                // leaving, and whether the receiver is answering. Feedback arriving at all proves it
                // is parsing our RTP, which halves the search space.
                if (now - lastStatsLog >= STATS_LOG_INTERVAL_MS) {
                    lastStatsLog = now
                    val summary = senders.entries.joinToString(" ") { (kind, sender) ->
                        "$kind=${sender.stats.packets}pkt/${sender.stats.octets}B"
                    }
                    Log.i(
                        TAG,
                        "$summary feedback=$feedbackPackets unmatchedRtcp=$unmatchedPackets",
                    )
                }
                var packet = udp.receive()
                while (packet != null) {
                    handleFeedback(packet)
                    packet = udp.receive()
                }
                // A receiver whose port has gone unreachable is not coming back, and spinning at it
                // forever hides the failure from the user behind a notification that says
                // "Mirroring your screen".
                if (udp.receiverGone) {
                    Log.w(TAG, "the receiver stopped listening; ending the mirror")
                    onStopped(MirrorStopReason.ReceiverGone)
                    return@launch
                }
                delay(RTCP_POLL_MS)
            }
        }
    }

    /**
     * Route one datagram to whichever stream it is feedback for.
     *
     * The SSRC pair identifies the stream, so a packet that matches neither is not ours and is
     * dropped without comment - a receiver also sends receiver reports and event logs we ignore.
     */
    private fun handleFeedback(packet: ByteArray) {
        for (stream in negotiation.streams) {
            val sender = senders[stream.kind] ?: continue
            val feedback = CastRtcp.parse(
                packet = packet,
                receiverSsrc = stream.receiverSsrc,
                senderSsrc = stream.senderSsrc,
                maxFrameId = sender.lastFrameId,
            ) ?: continue
            feedbackPackets++
            if (hexDump) {
                Log.i(
                    TAG,
                    "${stream.kind} feedback checkpoint=${feedback.checkpoint} " +
                        "nacks=${feedback.nacks.size} acks=${feedback.ackedFrames.size} " +
                        "pli=${feedback.pictureLoss} playoutDelay=${feedback.playoutDelayMs}",
                )
            }
            val recovery = session.onFeedback(feedback)
            sender.retransmit(recovery.retransmissions)
            if (stream.kind == StreamKind.Video &&
                (recovery.needsKeyFrame || feedback.pictureLoss)
            ) {
                // Either the receiver asked outright (PLI) or it has fallen further behind than the
                // retransmit buffer can repair. A key frame is the only way out of both.
                Log.i(TAG, "key frame requested (pli=${feedback.pictureLoss})")
                videoEncoder?.requestKeyFrame()
            }
            return
        }
        // Receiver reports and event logs also arrive here and are none of our business, but a run
        // where *everything* is unmatched means the SSRC pairing is wrong.
        unmatchedPackets++
    }

    /**
     * Stop everything, in an order that does not race.
     *
     * The loops are cancelled **and joined** before anything they touch is released: they call into
     * `MediaCodec` and `AudioRecord` directly, and releasing either underneath a thread parked
     * inside it is a native-side crash rather than a catchable exception. `runBlocking` is
     * acceptable here because the loops only ever park for a few milliseconds.
     */
    fun stop() {
        val jobs = listOfNotNull(videoJob, audioJob, rtcpJob)
        videoJob = null
        audioJob = null
        rtcpJob = null
        runCatching {
            runBlocking {
                for (job in jobs) {
                    job.cancel()
                    job.join()
                }
            }
        }
        // The display goes before the encoder: it is what is writing into the encoder's surface.
        capture?.release()
        videoEncoder?.release()
        audioEncoder?.release()
        transport?.close()
        capture = null
        videoEncoder = null
        audioEncoder = null
        transport = null
        senders.clear()
        scope.cancel()
    }

    /** 32 bits of seconds since 1900, then 32 bits of fraction. */
    private fun ntpTimestamp(millis: Long): Long {
        val seconds = millis / 1000 + NTP_UNIX_OFFSET_SECONDS
        val fraction = (millis % 1000) * (1L shl 32) / 1000
        return (seconds shl 32) or fraction
    }
}
