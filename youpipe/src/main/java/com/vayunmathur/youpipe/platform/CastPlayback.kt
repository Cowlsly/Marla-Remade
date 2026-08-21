package com.vayunmathur.youpipe.platform

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.vayunmathur.sdk.cast.CastClient
import com.vayunmathur.sdk.cast.CastContract
import com.vayunmathur.sdk.cast.CastException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "CastPlayback"

/**
 * YouPipe's cast session: where the video goes, and where the audio goes.
 *
 * An object because the two halves live in different places and both have to reach it. The `Surface`
 * is consumed by `VideoPlayer`, which hands it to the `MediaController`; the PCM is produced inside
 * [PlaybackService]'s audio sink, which has no reference to any of the UI. They are in the same process
 * - `PlaybackService` is a `MediaSessionService` in this app - so a singleton is enough and no second
 * IPC hop is needed.
 *
 * **The reason a URL is not handed over instead.** YouPipe's stream URLs are usually synthetic
 * (`sabr://<videoId>?v=<itag>`), resolvable only in-process by `SabrNgDashMediaSource` with a PO token
 * minted through a WebView, and the receiver is deliberately on a LAN with no internet. So what crosses
 * to the TV is pixels and PCM, which is also why ExoPlayer keeps decoding and keeps its transport
 * controls: only the render target changes.
 */
object CastPlayback {

    /** Where the cast is. */
    sealed interface State {

        /** Playing locally. */
        data object Idle : State

        /** The picker is up, or the session is being negotiated with the TV. */
        data object Connecting : State

        /**
         * On the TV. [surface] is what the player must render into, and it belongs to Cast - it must
         * not be released here, which is what would break the *next* cast rather than this one.
         */
        data class Casting(
            val surface: Surface,
            val receiverName: String,
            val width: Int,
            val height: Int,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var client: CastClient? = null

    fun support(context: Context): CastClient.Support = CastClient(context).support()

    /** True while the picker or the negotiation is in flight, so a second tap does nothing. */
    fun markConnecting() {
        _state.value = State.Connecting
    }

    /**
     * Open a session for a [width] x [height] frame and start the audio tap.
     *
     * The geometry is a request: Cast clamps it to the TV's decoder limits and to this phone's own
     * encoder, and [State.Casting] carries what was actually granted.
     *
     * Returns the failure rather than throwing, because every caller here is a button and every
     * failure is a message.
     */
    suspend fun open(context: Context, width: Int, height: Int): CastException? {
        close()
        _state.value = State.Connecting
        val newClient = CastClient(context)
        return try {
            val session = newClient.openSession(width, height, wantAudio = true)
            client = newClient
            newClient.onEnded = { reason ->
                Log.i(TAG, "the cast session ended (reason $reason)")
                close()
            }
            CastAudioTap.attach(session.audio)
            _state.value = State.Casting(
                surface = session.surface,
                receiverName = session.receiverName,
                width = session.width,
                height = session.height,
            )
            null
        } catch (e: CastException) {
            newClient.close()
            _state.value = State.Idle
            e
        }
    }

    /** Back to playing locally. Idempotent. */
    fun close() {
        CastAudioTap.detach()
        client?.close()
        client = null
        _state.value = State.Idle
    }
}

/**
 * The PCM tap, as a `TeeAudioProcessor.AudioBufferSink`.
 *
 * Sits in `PlaybackService`'s audio-sink processor chain, so it sees the decoded samples on their way
 * to the speaker. Local output is muted with `volume = 0f` rather than by stopping the renderer,
 * because volume is applied in the sink *after* the processor chain - the tap still sees full-scale
 * PCM.
 *
 * **Never blocks the audio thread.** `handleBuffer` runs on the playback thread, and a direct write
 * into a full pipe would stall decoding - which is exactly what happens if the reader on the Cast side
 * stops. So buffers go into a bounded queue and a writer thread drains it; when the queue is full the
 * oldest buffer is dropped, which costs a few milliseconds of audio rather than the whole playback.
 */
@OptIn(UnstableApi::class)
object CastAudioTap : TeeAudioProcessor.AudioBufferSink {

    /** ~0.2 s of 20 ms buffers: long enough to ride out a scheduling hiccup, short enough not to lag. */
    private const val QUEUE_CAPACITY = 16

    private const val ENCODING_PCM_16BIT = android.media.AudioFormat.ENCODING_PCM_16BIT

    @Volatile
    private var out: FileOutputStream? = null

    @Volatile
    private var pipe: ParcelFileDescriptor? = null

    private var writer: Thread? = null

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    /** The format the processor chain is handing us, from the last [flush]. */
    @Volatile
    private var sampleRate = CastContract.AUDIO_SAMPLE_RATE

    @Volatile
    private var channelCount = CastContract.AUDIO_CHANNELS

    @Volatile
    private var supported = false

    /** Start writing into [audio]'s pipe. Null means the session has no audio; the tap stays off. */
    fun attach(audio: ParcelFileDescriptor?) {
        detach()
        if (audio == null) return
        pipe = audio
        out = FileOutputStream(audio.fileDescriptor)
        writer = Thread({ drain() }, "cast-pcm-writer").apply {
            isDaemon = true
            start()
        }
    }

    fun detach() {
        val thread = writer
        writer = null
        out = null
        thread?.interrupt()
        queue.clear()
        runCatching { pipe?.close() }
        pipe = null
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        this.channelCount = channelCount
        // Float output would need a different conversion and is off by default; anything other than
        // interleaved 16-bit is refused rather than sent as noise.
        supported = encoding == ENCODING_PCM_16BIT && sampleRateHz > 0 && channelCount > 0
        if (!supported) {
            Log.w(TAG, "cannot tap audio at ${sampleRateHz}Hz, $channelCount ch, encoding $encoding")
        }
        queue.clear()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (out == null || !supported) return
        val converted = toContractFormat(buffer) ?: return
        // Drop the oldest rather than block: this is the audio thread.
        if (!queue.offer(converted)) {
            queue.poll()
            queue.offer(converted)
        }
    }

    private fun drain() {
        while (!Thread.currentThread().isInterrupted) {
            val chunk = try {
                queue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            } ?: continue
            val stream = out ?: return
            try {
                stream.write(chunk)
            } catch (e: Exception) {
                // The Cast side closed the pipe; there is nothing to recover and nothing to say to
                // the user, because the session teardown already will.
                Log.i(TAG, "the PCM pipe closed", e)
                return
            }
        }
    }

    /**
     * Whatever the decoder produced, as 48 kHz stereo 16-bit little-endian.
     *
     * Opus - which is what YouPipe plays almost always - is already 48 kHz stereo, so the common path
     * is a straight copy. The conversions exist for the AAC fallback, which is often 44.1 kHz: without
     * them that video would cast with silent audio and nothing to indicate why.
     */
    private fun toContractFormat(buffer: ByteBuffer): ByteArray? {
        val remaining = buffer.remaining()
        if (remaining <= 0) return null
        val bytes = ByteArray(remaining)
        // Read a duplicate: TeeAudioProcessor passes this same buffer on to the sink afterwards, so
        // consuming it here would silence local playback and, worse, the rest of the chain.
        buffer.duplicate().get(bytes)
        if (sampleRate == CastContract.AUDIO_SAMPLE_RATE &&
            channelCount == CastContract.AUDIO_CHANNELS
        ) {
            return bytes
        }
        val frames = remaining / (2 * channelCount)
        if (frames == 0) return null
        val outFrames = (frames.toLong() * CastContract.AUDIO_SAMPLE_RATE / sampleRate).toInt()
        if (outFrames == 0) return null
        val result = ByteArray(outFrames * 2 * CastContract.AUDIO_CHANNELS)
        for (frame in 0 until outFrames) {
            // Nearest source frame. Resampling per buffer rather than continuously leaves a sub-sample
            // discontinuity at each boundary, which is inaudible next to the alternative of no audio.
            val source = (frame.toLong() * frames / outFrames).toInt().coerceAtMost(frames - 1)
            val base = source * 2 * channelCount
            val left = sampleAt(bytes, base)
            val right = if (channelCount >= 2) sampleAt(bytes, base + 2) else left
            val target = frame * 4
            result[target] = (left and 0xFF).toByte()
            result[target + 1] = (left shr 8 and 0xFF).toByte()
            result[target + 2] = (right and 0xFF).toByte()
            result[target + 3] = (right shr 8 and 0xFF).toByte()
        }
        return result
    }

    /** One little-endian 16-bit sample, as its raw bits. */
    private fun sampleAt(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)
    }
}
