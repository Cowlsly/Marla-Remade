package com.vayunmathur.cast.platform.mirror

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import com.vayunmathur.cast.network.CastUdpTransport
import com.vayunmathur.cast.protocol.NegotiatedStream
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.Rtcp
import com.vayunmathur.cast.protocol.StreamKind
import com.vayunmathur.cast.protocol.StreamingSession
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
enum class MirrorStopReason { Udp, NoEncoders, ReceiverGone }

/**
 * The running mirror: capture and encode in, RTP out, RTCP back.
 *
 * Holds no protocol knowledge - [StreamingSession] decides what to retransmit, `RtpPacketizer` decides
 * byte layout, [StreamSender] does the per-stream work. This class is the part that cannot be
 * unit-tested, so it is kept to the wiring and two loops.
 *
 * [source] is the only thing that differs between mirroring the screen and streaming another app's
 * content: with [MirrorSource.Screen] a `VirtualDisplay` writes into the encoder's input surface and
 * `AudioPlaybackCapture` supplies the PCM; with [MirrorSource.Content] the input surface is **handed
 * out** through [contentSurface] and the PCM arrives through [audioWriteEnd]'s pipe. Everything after
 * that point - the same [VideoEncoder], the same [StreamSender], the same RTCP loop - is shared.
 *
 * [receiverHost] is passed in because `STREAM_READY` carries only a port; the address is the one the
 * control channel is already talking to.
 *
 * The context is reduced to the application context on the way in: this is reachable from
 * `CastController`, which is an object, so holding the Service that started mirroring would outlive it.
 * Only `WindowManager` metrics are read from it, which the application context serves fine.
 */
class MirrorEngine(
    context: Context,
    private val source: MirrorSource,
    private val receiverHost: String,
    private val negotiation: Negotiation,
    private val geometry: CaptureGeometry,
    /** The negotiated frame rate, which is the TV's cap rather than a fixed 30. */
    private val frameRate: Int,
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
    private var audioEncoder: AudioStream? = null

    /**
     * The encoder's input surface, for a [MirrorSource.Content] session to draw into. Null for
     * screen mirroring, where a `VirtualDisplay` already owns it.
     *
     * Valid only between a successful [start] and [stop]: [VideoEncoder.release] releases it, which is
     * correct - the client holds its own Binder-duplicated copy and must not release that one.
     */
    var contentSurface: Surface? = null
        private set

    /**
     * The write end of the PCM pipe, for a [MirrorSource.Content] session that asked for audio.
     *
     * **The caller must close it once it has been sent.** A `ParcelFileDescriptor` is duplicated by
     * the Binder transaction, so keeping this copy open would hold the pipe open from our side and the
     * read end would never see the client stop writing.
     */
    var audioWriteEnd: ParcelFileDescriptor? = null
        private set

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
        val encoder = VideoEncoder(
            width = geometry.width,
            height = geometry.height,
            frameRate = frameRate,
            bitRate = geometry.bitRate,
        )
        if (!encoder.start()) return false
        val surface = encoder.inputSurface
        if (surface == null) {
            encoder.release()
            return false
        }
        // The one branch: mirror the screen into the surface, or hand it to whoever asked for it.
        when (source) {
            is MirrorSource.Screen -> {
                val screen = ScreenCapture(source.projection)
                if (!screen.start(surface, geometry)) {
                    encoder.release()
                    screen.release()
                    return false
                }
                capture = screen
            }
            is MirrorSource.Content -> contentSurface = surface
        }
        Log.i(
            TAG,
            "streaming ${geometry.width}x${geometry.height} " +
                "@ ${geometry.bitRate / 1_000_000.0} Mbit/s" +
                if (source.appLabel.isEmpty()) "" else " from ${source.appLabel}",
        )
        videoEncoder = encoder
        val sender = StreamSender(stream, udp, StreamingSession())
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
        val encoder = when (source) {
            is MirrorSource.Screen -> AudioEncoder(source.projection)
            is MirrorSource.Content -> {
                if (!source.wantAudio) return false
                val pipe = try {
                    ParcelFileDescriptor.createPipe()
                } catch (e: Exception) {
                    Log.w(TAG, "could not create the PCM pipe", e)
                    return false
                }
                audioWriteEnd = pipe[1]
                PcmAudioEncoder(pipe[0])
            }
        }
        if (!encoder.start()) {
            encoder.release()
            runCatching { audioWriteEnd?.close() }
            audioWriteEnd = null
            return false
        }
        audioEncoder = encoder
        val sender = StreamSender(stream, udp, StreamingSession())
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
                            Rtcp.senderReport(
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
            val feedback = Rtcp.parse(
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
            val recovery = sender.onFeedback(feedback)
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
        // Only our own copy; the client's Binder-duplicated one is closed when the client goes away.
        runCatching { audioWriteEnd?.close() }
        capture = null
        videoEncoder = null
        audioEncoder = null
        transport = null
        contentSurface = null
        audioWriteEnd = null
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
