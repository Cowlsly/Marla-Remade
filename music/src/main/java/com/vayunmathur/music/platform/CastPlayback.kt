package com.vayunmathur.music.platform

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.net.toUri
import com.vayunmathur.library.media.OpusTranscoder
import com.vayunmathur.music.data.Music
import com.vayunmathur.sdk.cast.CastClient
import com.vayunmathur.sdk.cast.CastException
import com.vayunmathur.sdk.cast.CastResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "CastPlayback"

/**
 * Plays this library on a television, by serving it rather than streaming pixels.
 *
 * Casting music was not possible before: the SDK's only session handed back a `Surface` to draw
 * into, and a music player has nothing to draw. A content session has no surface at all - Cast runs
 * an HTTPS proxy, asks this app for a descriptor per track, and the TV decodes the original bytes.
 * So the phone starts no encoder, and the one thing it does encode is Opus, once per file.
 *
 * **Preparation happens before the TV is told to play, deliberately.** Cast's proxy holds an HTTP
 * request open while it waits for a descriptor, so transcoding inside that wait would turn a slow
 * conversion into a stalled fetch. Doing it first makes the delay this app's own, where it can be
 * shown as one.
 */
object CastPlayback {

    sealed interface State {

        data object Idle : State

        /** The picker has been dismissed and the session is being negotiated. */
        data object Connecting : State

        data class Casting(
            val receiverName: String,
            /**
             * A track is being converted to Opus.
             *
             * Worth a state of its own because it is the only part of casting that takes visible
             * time, and a first play with no explanation looks like a hang.
             */
            val preparing: Boolean = false,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var client: CastClient? = null

    /**
     * What the TV has been told about, by resource id.
     *
     * Also the authorisation list: a request for anything this app did not offer is answered with
     * nothing, which the TV sees as a `404`. Cast is trusted, but a bug on either side should not be
     * able to turn the proxy into a reader of the whole music library.
     */
    private val offered = ConcurrentHashMap<String, Prepared>()

    /** A track that is ready to be served, and where its bytes are. */
    private class Prepared(
        val source: Source,
        val contentType: String,
    )

    private sealed interface Source {
        /** Already 48 kHz Opus, so the file itself is served with no processing at all. */
        class Original(val uri: Uri) : Source

        /** Converted once and cached, keyed by track id. */
        class Cached(val file: File) : Source
    }

    fun support(context: Context): CastClient.Support = CastClient(context).support()

    val isCasting: Boolean get() = _state.value is State.Casting

    /**
     * Open an audio-only session on the TV the user picked.
     *
     * Requires a TV already connected, which the picker Activity is for. Returns false with the state
     * back at [State.Idle] if Cast refused - most usefully when the television has no Opus decoder,
     * which it now says rather than playing silence.
     */
    suspend fun open(context: Context): Boolean {
        close()
        _state.value = State.Connecting
        val newClient = CastClient(context)
        return try {
            val session = newClient.openContentSession(
                resources = { resourceId -> openResource(context, resourceId) },
                // No picture. This is the case the Surface contract could not express at all.
                video = false,
            )
            newClient.onEnded = {
                Log.i(TAG, "the TV ended the session")
                clear()
            }
            client = newClient
            _state.value = State.Casting(session.receiverName)
            Log.i(TAG, "casting to '${session.receiverName}'")
            true
        } catch (e: CastException) {
            Log.w(TAG, "could not start casting", e)
            newClient.close()
            _state.value = State.Idle
            false
        }
    }

    /**
     * Put [song] on the TV, converting it first if it is not already Opus.
     *
     * Suspends for as long as the conversion takes, which is why the state carries a `preparing`
     * flag: a four-minute track is a second or two the first time and nothing at all afterwards.
     */
    suspend fun play(context: Context, song: Music) {
        val active = client ?: return
        val id = song.id.toString()
        val known = offered[id]
        if (known != null) {
            active.play(id, known.contentType, song.duration)
            return
        }

        _state.update { if (it is State.Casting) it.copy(preparing = true) else it }
        val prepared = withContext(Dispatchers.IO) { prepare(context, song) }
        _state.update { if (it is State.Casting) it.copy(preparing = false) else it }

        if (prepared == null) {
            Log.w(TAG, "'${song.title}' could not be prepared for casting")
            return
        }
        offered[id] = prepared
        active.play(id, prepared.contentType, song.duration)
    }

    fun close() {
        client?.close()
        clear()
    }

    private fun clear() {
        client = null
        offered.clear()
        _state.value = State.Idle
    }

    // ------------------------------------------------------------------
    // Getting a track into a shape the TV can play
    // ------------------------------------------------------------------

    /**
     * Serve an Opus file as it is; convert anything else, once.
     *
     * Converting to a file rather than streaming a live encode is the whole reason scrubbing works: a
     * live transcode has no length and no seekable offsets, so `Range` - and therefore the seek bar -
     * would have nothing to answer with.
     */
    private fun prepare(context: Context, song: Music): Prepared? {
        val uri = song.uri.toUri()
        if (isOpus(context, uri)) {
            Log.i(TAG, "'${song.title}' is already Opus; serving it unchanged")
            return Prepared(Source.Original(uri), CONTENT_TYPE)
        }

        val cached = cacheFile(context, song.id)
        if (cached.length() > 0) {
            Log.i(TAG, "'${song.title}' was converted on an earlier cast; reusing it")
            return Prepared(Source.Cached(cached), CONTENT_TYPE)
        }

        val source = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null

        val encoded = OpusTranscoder.transcode(source, isStopped = { false }) {} ?: return null
        return runCatching {
            cached.parentFile?.mkdirs()
            cached.writeBytes(encoded)
            Log.i(TAG, "converted '${song.title}' to ${encoded.size} bytes of Opus")
            Prepared(Source.Cached(cached), CONTENT_TYPE)
        }.getOrNull()
    }

    /**
     * Answers Cast's request for a track's bytes.
     *
     * Called on Cast's own background thread and expected to be quick, which it is: everything slow
     * already happened in [prepare]. The descriptor must be seekable, because the TV asks for byte
     * ranges - both branches here give a real file, and neither is a pipe.
     */
    private fun openResource(context: Context, resourceId: String): CastResource? {
        val prepared = offered[resourceId] ?: run {
            Log.w(TAG, "asked for '$resourceId', which was never offered")
            return null
        }
        return runCatching {
            when (val source = prepared.source) {
                is Source.Original -> {
                    val descriptor = context.contentResolver.openFileDescriptor(source.uri, "r")
                        ?: return null
                    CastResource(descriptor, descriptor.statSize, prepared.contentType)
                }
                is Source.Cached -> CastResource(
                    ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY),
                    source.file.length(),
                    prepared.contentType,
                )
            }
        }.onFailure { Log.w(TAG, "could not open '$resourceId'", it) }.getOrNull()
    }

    /**
     * Whether the file is already Ogg/Opus, from its first bytes rather than its name.
     *
     * `MediaStore` has no column for this and `getType` reports `audio/ogg` for Vorbis too, so the
     * container is read: an Ogg page header, then `OpusHead` inside the first page. Getting this
     * wrong in the permissive direction would serve a Vorbis file as Opus and the TV would refuse
     * it, so the check errs towards converting.
     */
    private fun isOpus(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(SNIFF_BYTES)
            val read = input.read(head)
            if (read < 36) return@use false
            val magic = String(head, 0, 4, Charsets.ISO_8859_1)
            magic == "OggS" && String(head, 0, read, Charsets.ISO_8859_1).contains("OpusHead")
        } == true
    }.getOrDefault(false)

    private fun cacheFile(context: Context, songId: Long): File =
        File(File(context.cacheDir, CACHE_DIR), "$songId.opus")

    /**
     * Clears converted tracks.
     *
     * The cache is what makes a second cast of the same track free, so it is kept across sessions -
     * but it is a cache, and 256 kbps Opus of a whole library would not be small.
     */
    fun clearCache(context: Context) {
        runCatching { File(context.cacheDir, CACHE_DIR).deleteRecursively() }
    }

    /** Enough to cover an Ogg page header and the `OpusHead` packet that follows it. */
    private const val SNIFF_BYTES = 64

    private const val CACHE_DIR = "cast-opus"

    /**
     * `audio/ogg` rather than `audio/opus`: the container is Ogg, and that is what decides which
     * extractor the TV's player reaches for.
     */
    private const val CONTENT_TYPE = "audio/ogg"
}
