package com.vayunmathur.openassistant.util

import android.graphics.BitmapFactory
import android.util.Log
import com.vayunmathur.library.ml.Gemma4AudioHandle
import com.vayunmathur.library.ml.Gemma4Handle
import com.vayunmathur.library.ml.Gemma4VisionHandle
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A chat turn on Gemma 4, replacing `com.google.ai.edge.litertlm`'s `Conversation`.
 *
 * # What this owns that the SDK used to
 *
 * The tool loop. litertlm's `automaticToolCalling = true` hid a generate / parse / invoke /
 * resume cycle; [ask] is that cycle, written out. Everything else the SDK did - the template,
 * the tokenizer, sampling - lives in [Gemma4Handle] and native.
 *
 * # One conversation at a time
 *
 * [Gemma4Handle] holds a single KV cache, so two turns cannot run at once. The lock is held for a
 * whole turn rather than per call, because a turn is many calls and interleaving them would mix
 * two conversations into one cache. `InferenceService` already serialises through a queue; the
 * lock is here so that a second caller blocks rather than corrupts.
 */
class Gemma4Engine(private val directory: File) : AutoCloseable {

    private val lock = ReentrantLock()
    private var handle: Gemma4Handle? = null

    /** Whether the baked prefix cache has been offered to this handle yet. */
    private var seededPrefix = false
    private var vision: Gemma4VisionHandle? = null
    private var audio: Gemma4AudioHandle? = null

    /** Whether images can be read. False leaves the assistant answering text. */
    val canSeeImages: Boolean
        get() = lock.withLock { vision?.isAvailable == true }

    /** Whether audio can be heard. False leaves the assistant answering text. */
    val canHearAudio: Boolean
        get() = lock.withLock { audio?.isAvailable == true }

    /** Whether the model is loaded and usable. */
    val isReady: Boolean
        get() = lock.withLock { handle?.isAvailable == true }

    /**
     * Load the model if it is not loaded. Returns whether it is usable afterwards.
     *
     * Idempotent, so `onCreate` can pre-warm and the first turn can call it again without cost.
     * Loading is slow - gigabytes of weights stream to the GPU - so callers should pre-warm off
     * the main thread.
     */
    fun ensureLoaded(): Boolean = lock.withLock {
        val live = handle
        if (live != null && live.isAvailable) return@withLock true
        live?.close()
        val opened = Gemma4Handle.inDirectory(directory)
        if (!opened.isAvailable) {
            Log.w(TAG, "gemma4 did not load from $directory")
            opened.close()
            handle = null
            return@withLock false
        }
        handle = opened
        seededPrefix = false
        // One measurement at load, logged. See `Gemma4Handle.benchmark`.
        opened.benchmark()
        // The vision tower is optional and downloaded separately, so a device without it still
        // gets a working assistant - `canSeeImages` is how the caller finds out.
        if (vision?.isAvailable != true) {
            vision?.close()
            val tower = Gemma4VisionHandle.inDirectory(directory)
            vision = if (tower.isAvailable) tower else { tower.close(); null }
        }
        // The audio tower is optional and downloaded separately in the same way, so a device
        // without it still gets an assistant that answers text - `canHearAudio` is how the caller
        // finds out.
        if (audio?.isAvailable != true) {
            audio?.close()
            val tower = Gemma4AudioHandle.inDirectory(directory)
            audio = if (tower.isAvailable) tower else { tower.close(); null }
        }
        true
    }

    /**
     * Soft tokens for each readable image in [paths], in order.
     *
     * Unreadable files and images the tower refuses are dropped rather than failing the turn:
     * losing one attachment is better than losing the reply. The caller compares the count with
     * what it passed to decide what to tell the model.
     */
    fun encodeImages(paths: List<String>): List<FloatArray> = lock.withLock {
        val tower = vision ?: return@withLock emptyList()
        paths.mapNotNull { path ->
            val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            if (bitmap == null) {
                Log.w(TAG, "cannot decode $path")
                return@mapNotNull null
            }
            val soft = tower.encode(bitmap)
            bitmap.recycle()
            if (soft == null) Log.w(TAG, "the vision tower refused $path")
            soft
        }
    }

    /**
     * Soft tokens for each readable clip in [paths], in order.
     *
     * Drops what it cannot use exactly as [encodeImages] does - a clip that will not parse, one
     * the tower refuses for being shorter than its attention band, or every clip on a device
     * without the tower. The caller compares the count with what it passed.
     *
     * Only the 16 kHz mono PCM WAV that `WavRecorder` writes is read; see [readWavMono16k].
     */
    fun encodeAudio(paths: List<String>): List<FloatArray> = lock.withLock {
        val tower = audio ?: return@withLock emptyList()
        paths.mapNotNull { path ->
            val samples = readWavMono16k(path) ?: return@mapNotNull null
            val soft = tower.encode(samples)
            if (soft == null) Log.w(TAG, "the audio tower refused $path")
            soft
        }
    }

    /**
     * Positions the prompt for [conversation] would occupy, against [promptCeiling].
     *
     * For a caller dropping history before a turn: [Gemma4Handle.generate] refuses an over-long
     * prompt outright, so somebody has to shrink it first, and only the caller knows which turns
     * a user can afford to lose.
     */
    fun positionsFor(
        conversation: List<Gemma4Handle.Turn>,
        system: String?,
        tools: ToolRegistry?,
        limit: Int = Gemma4Handle.DEFAULT_REPLY,
    ): Int = lock.withLock {
        val live = handle ?: return@withLock 0
        live.positionsFor(conversation, system, tools?.declarations ?: emptyList(), limit)
    }

    /** What [positionsFor] must not exceed if the reply is to keep its [limit]. */
    fun promptCeiling(limit: Int = Gemma4Handle.DEFAULT_REPLY): Int =
        Gemma4Handle.promptCeiling(limit)

    /**
     * Run a turn, resolving any tool calls, and stream the visible reply to [onPartial].
     *
     * [onPartial] returning false stops generation - cancellation, and the early-halt the
     * structured-extraction path uses.
     *
     * Returns the reply with all tool syntax stripped, or null if the model is unavailable.
     *
     * # The loop
     *
     * The model may answer, or may ask for a tool. If it asks, the call is invoked and both the
     * call and its result are appended to the model's own turn, then generation resumes from the
     * extended prompt. [MAX_TOOL_HOPS] bounds it: a model that loops on a failing tool would
     * otherwise never return.
     */
    fun ask(
        conversation: List<Gemma4Handle.Turn>,
        system: String?,
        tools: ToolRegistry?,
        limit: Int = Gemma4Handle.DEFAULT_REPLY,
        onPartial: (String) -> Boolean = { true },
    ): String? = lock.withLock {
        val live = handle ?: return@withLock null
        if (!live.isAvailable) return@withLock null
        val declarations = tools?.declarations ?: emptyList()
        // Once per load: seed the caches from the baked prefix so the ~1,900 positions of system
        // block and tool declarations are never prefilled here. `loadPrefix` re-encodes the text
        // and refuses unless its digest matches what was baked, so a prompt that has changed
        // since costs a prefill rather than an answer to a question nobody asked.
        if (!seededPrefix) {
            seededPrefix = true
            val prefix = "<bos><|turn>system\n" + system.orEmpty() +
                Gemma4Handle.declareTools(declarations)
            if (!live.loadPrefix(prefix)) {
                Log.i(TAG, "no usable prefix cache; the prompt will be prefilled")
            }
        }

        // `pending` accumulates the model's own turn across tool hops: the call it made, the
        // result it got, and finally the prose. It is fed back as part of the prompt so the model
        // sees its own request when it resumes.
        var pending = ""
        var visible = ""
        for (hop in 0..MAX_TOOL_HOPS) {
            // `pending` is the model's own half-written turn - the call it made and the
            // result it got - and it goes in as a *continuation* so it lands inside the open
            // model turn. Appending it as another `Turn` closed that turn and opened a new one.
            val history = conversation
            var stopped = false
            val reply = live.generate(history, system, declarations, limit, pending) { partial ->
                // Tool syntax is machine chatter and must not reach the UI, so only the prose
                // before any call marker is streamed.
                val shown = visible + strip(partial)
                if (onPartial(shown)) {
                    // A complete call means this hop is over; stop rather than let the model
                    // carry on writing past a request it is waiting on.
                    Gemma4Handle.parseToolCall(partial) == null
                } else {
                    stopped = true
                    false
                }
            } ?: return@withLock null

            visible += strip(reply)
            if (stopped) return@withLock visible
            val call = Gemma4Handle.parseToolCall(reply)
            if (call == null || tools == null) return@withLock visible
            if (hop == MAX_TOOL_HOPS) {
                Log.w(TAG, "stopping after $MAX_TOOL_HOPS tool hops")
                return@withLock visible
            }
            val result = tools.invoke(call)
            Log.d(TAG, "tool ${call.name}(${call.arguments}) -> ${result.take(120)}")
            pending += reply.substringBefore("<tool_call|>") + "<tool_call|>" +
                Gemma4Handle.renderToolResponse(call.name, result)
        }
        visible
    }

    /** Discard the conversation state. Cheap - see [Gemma4Handle.reset]. */
    fun reset() = lock.withLock { handle?.reset() }

    override fun close() = lock.withLock {
        handle?.close()
        handle = null
        vision?.close()
        vision = null
        audio?.close()
        audio = null
    }

    companion object {
        private const val TAG = "Gemma4Engine"

        /** The one `wFormatTag` [readWavMono16k] accepts: uncompressed integer PCM. */
        private const val WAV_PCM = 1

        /**
         * 16 kHz mono samples from a PCM WAV file, or null if it is not one.
         *
         * Only what `WavRecorder` writes is accepted. There is no resampler and no downmix here,
         * so another rate, width or channel count is refused rather than converted: [encodeAudio]
         * then drops the clip, which is better than handing the tower a waveform it reads as
         * noise.
         */
        private fun readWavMono16k(path: String): FloatArray? {
            val bytes = runCatching { File(path).readBytes() }.getOrElse {
                Log.w(TAG, "cannot read $path: $it")
                return null
            }
            if (bytes.size < 12 || chunkId(bytes, 0) != "RIFF" || chunkId(bytes, 8) != "WAVE") {
                Log.w(TAG, "$path is not a WAV file")
                return null
            }
            val wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var described = false
            var dataAt = -1
            var dataBytes = 0
            var at = 12
            while (at + 8 <= bytes.size) {
                val size = wav.getInt(at + 4)
                if (size < 0) break
                val body = at + 8
                when (chunkId(bytes, at)) {
                    "fmt " -> {
                        if (size < 16 || body + 16 > bytes.size) return null
                        val pcm = wav.getShort(body).toInt() == WAV_PCM
                        val mono = wav.getShort(body + 2).toInt() == 1
                        val rate = wav.getInt(body + 4)
                        val width = wav.getShort(body + 14).toInt()
                        if (!pcm || !mono || rate != Gemma4AudioHandle.SAMPLE_RATE || width != 16) {
                            Log.w(TAG, "$path is not 16 kHz mono 16-bit PCM")
                            return null
                        }
                        described = true
                    }

                    "data" -> {
                        dataAt = body
                        dataBytes = minOf(size, bytes.size - body)
                    }
                }
                // Chunks are word-aligned, so an odd size is followed by a pad byte.
                at = body + size + (size and 1)
            }
            if (!described || dataAt < 0) {
                Log.w(TAG, "$path has no 16-bit PCM data")
                return null
            }
            // Native truncates to `MAX_SAMPLES` regardless, so a long recording is cut here
            // rather than widened to floats first.
            val count = minOf(dataBytes / 2, Gemma4AudioHandle.MAX_SAMPLES)
            val pcm = ByteBuffer.wrap(bytes, dataAt, dataBytes)
                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(count) { pcm.get(it) / 32768f }
        }

        private fun chunkId(bytes: ByteArray, at: Int): String =
            String(bytes, at, 4, Charsets.US_ASCII)

        /**
         * Tool calls one turn may make before the loop gives up.
         *
         * Eight is generous for the 24 tools `AssistantToolSet` declares - a realistic turn makes
         * one or two - and bounds the pathological case where a tool keeps failing and the model
         * keeps retrying it.
         */
        const val MAX_TOOL_HOPS = 8

        /**
         * The prose in a reply, with the tool markers and everything inside them removed.
         *
         * Streamed partials arrive mid-marker, so a half-written `<|tool_call>` must not flash up
         * in the UI: anything from an opening marker onwards is dropped whether or not it has
         * been closed yet.
         */
        fun strip(reply: String): String {
            var out = reply
            for (marker in listOf("<|tool_call>", "<|tool_response>", "<|tool>")) {
                val at = out.indexOf(marker)
                if (at >= 0) out = out.substring(0, at)
            }
            // A partial marker at the very end - `<|too` - is also not prose.
            val open = out.lastIndexOf('<')
            if (open >= 0 && out.length - open <= "<|tool_response>".length) {
                val tail = out.substring(open)
                if ("<|tool_call>".startsWith(tail) || "<|tool_response>".startsWith(tail) ||
                    "<|tool>".startsWith(tail) || "<turn|>".startsWith(tail)
                ) {
                    out = out.substring(0, open)
                }
            }
            return out
        }
    }
}
