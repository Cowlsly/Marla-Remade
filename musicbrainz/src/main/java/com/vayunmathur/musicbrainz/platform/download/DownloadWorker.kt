package com.vayunmathur.musicbrainz.platform.download

import android.content.Context
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.musicbrainz.data.LocalTrack
import com.vayunmathur.musicbrainz.data.MusicBrainzRepository
import com.vayunmathur.musicbrainz.data.download.AudioQuery
import com.vayunmathur.musicbrainz.data.download.AudioSources
import com.vayunmathur.musicbrainz.data.download.Lyrics
import com.vayunmathur.musicbrainz.data.download.OggOpusTagger
import com.vayunmathur.musicbrainz.data.download.OpusRemuxer
import com.vayunmathur.musicbrainz.data.download.OpusTranscoder
import com.vayunmathur.musicbrainz.data.download.ResolvedAudio
import com.vayunmathur.musicbrainz.data.download.VorbisTags
import com.vayunmathur.musicbrainz.data.library.LibraryScanner
import com.vayunmathur.musicbrainz.domain.library.MatchKeys
import com.vayunmathur.musicbrainz.network.api.CoverArt
import com.vayunmathur.musicbrainz.platform.MusicBrainzPrefs
import com.vayunmathur.musicbrainz.platform.SafTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Fetches one track and files it into the user's music folder.
 *
 * Runs under WorkManager so an album's worth of downloads survives the app being
 * backgrounded, and so a failed track can be retried without redoing the rest.
 */
class DownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val request = inputData.toDownloadRequest() ?: return@withContext Result.failure()
        val key = request.key
        try {
            val prefs = MusicBrainzPrefs(applicationContext)
            val treeUri = prefs.musicFolderUri()
                ?: return@withContext fail(key, "No music folder selected")

            DownloadQueue.update(DownloadState.Searching, key)
            val audio = resolveAudio(request, prefs)
                ?: return@withContext fail(key, "No audio source found")

            DownloadQueue.update(DownloadState.Downloading, key)
            val raw = download(audio.urls) { progress ->
                DownloadQueue.update(DownloadState.Downloading, key, progress)
            } ?: return@withContext fail(key, "Download failed")

            DownloadQueue.update(DownloadState.Tagging, key)
            val cover = CoverArtCache.get(request.releaseId, request.releaseGroupId)
            val lyrics = Lyrics.fetch(request.artist, request.title, request.album, request.durationMs)
            android.util.Log.i(
                "MBDownload",
                "tagging '${request.title}': passthrough=${audio.isOpusPassthrough} " +
                    "source=${audio.suffix} bitrate=${audio.bitrate} raw=${raw.size} " +
                    "cover=${cover?.size ?: 0} lyrics=${lyrics?.length ?: 0}",
            )

            // Every download is filed as a tagged `.opus`. A stream that is already 48 kHz
            // Opus is only rewrapped into Ogg; everything else is re-encoded. A failure here
            // fails the download rather than writing one of the formats being replaced.
            val ogg = if (audio.isOpusPassthrough) {
                OpusRemuxer.remux(applicationContext, raw)
            } else {
                // Re-encoding is the slowest step in the download by a wide margin, so it
                // reports progress of its own; without it the row sits still long enough to
                // look like a hang and invite the user to cancel a working download.
                OpusTranscoder.transcode(raw, { isStopped }) { progress ->
                    DownloadQueue.update(DownloadState.Tagging, key, progress)
                }
            } ?: return@withContext fail(key, "Could not convert the download to Opus")

            val tagged = OggOpusTagger.tag(ogg, request.toVorbisTags(cover, lyrics)) ?: ogg
            android.util.Log.i(
                "MBDownload",
                "writing '${request.title}' as .opus: ogg=${ogg.size} tagged=${tagged.size}",
            )

            val written = writeToLibrary(treeUri, request, "opus", "audio/ogg", tagged)
                ?: return@withContext fail(key, "Could not write to music folder")

            recordInIndex(written, request, tagged.size)
            DownloadQueue.update(DownloadState.Done, key, 1f)
            Result.success()
        } catch (e: Exception) {
            fail(key, e.message ?: "Download failed")
        }
    }

    /**
     * Records a failure against the queue entry and gives up on the track.
     *
     * Silent when the worker was stopped: a cancelled download is the user's own action, and
     * telling them the conversion failed describes the app as broken when it did exactly
     * what they asked. Every abandoned step routes through here, so the stop check belongs
     * here rather than at each of them.
     */
    private fun fail(key: String, message: String): Result {
        if (isStopped) return Result.failure()
        DownloadQueue.update(DownloadState.Failed, key, error = message)
        return Result.failure()
    }

    /**
     * Asks each source in the user's order until one has a match.
     *
     * A source that throws is treated as no match: the point of the fallback order is that
     * a lapsed Tidal subscription degrades the download to YouTube rather than losing it.
     */
    private suspend fun resolveAudio(
        request: DownloadRequest,
        prefs: MusicBrainzPrefs,
    ): ResolvedAudio? {
        val query = AudioQuery(
            artist = request.artist,
            title = request.title,
            album = request.album,
            durationMs = request.durationMs,
            isrcs = request.isrcs,
        )
        for (source in AudioSources.ordered(applicationContext, prefs.downloadSource.first())) {
            val audio = runCatching { source.resolve(query) }.getOrNull()
            if (audio != null && audio.urls.isNotEmpty()) return audio
        }
        return null
    }

    /**
     * Streams the audio into memory, reporting progress as it goes.
     *
     * Buffered rather than written straight to the destination because the tagger has to
     * rewrite the container before the file is filed away, and a partially written track
     * appearing in the user's music folder would be picked up by every other player on
     * the device.
     *
     * [urls] is a single progressive stream for YouTube and one entry per DASH segment for
     * Tidal, so the parts are concatenated in order into the one buffer. A segmented stream
     * reports progress by segments finished: asking each of a few hundred segments for its
     * length first would double the requests and stall the download before it started.
     */
    private suspend fun download(urls: List<String>, onProgress: (Float) -> Unit): ByteArray? {
        val expected = if (urls.size == 1) NetworkClient.getContentLength(urls[0]) ?: 0L else 0L
        val buffer = ByteArrayOutputStream(if (expected > 0) expected.toInt() else DEFAULT_BUFFER)
        var lastReported = 0L
        for ((index, url) in urls.withIndex()) {
            if (isStopped) return null
            val response = NetworkClient.stream(url) { stream, resp ->
                if (stream == null || !resp.isSuccess) return@stream
                val chunk = ByteArray(READ_BUFFER)
                while (!stream.isClosedForRead) {
                    if (isStopped) return@stream
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                    if (expected > 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastReported > PROGRESS_INTERVAL_MS) {
                            lastReported = now
                            onProgress((buffer.size().toFloat() / expected).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            // A missing segment would leave a file that stops playing part-way through, so
            // a segmented stream fails outright rather than being written incomplete.
            if (!response.isSuccess && urls.size > 1) return null
            if (urls.size > 1) onProgress((index + 1).toFloat() / urls.size)
        }
        // Being stopped abandons the read mid-segment, so what is buffered is a truncated
        // file; writing it would leave the library holding a track that never plays through.
        if (isStopped) return null
        return buffer.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** Files the track as `<Album artist>/<Album>/NN Title.ext` under the chosen folder. */
    private fun writeToLibrary(
        treeUri: String,
        request: DownloadRequest,
        suffix: String,
        mimeType: String,
        bytes: ByteArray,
    ): android.net.Uri? {
        val tree = treeUri.toUri()
        val folderArtist = SafTree.sanitize(request.albumArtist ?: request.artist)
        val folderAlbum = SafTree.sanitize(request.album ?: request.title)
        val parent = SafTree.ensurePath(applicationContext, tree, listOf(folderArtist, folderAlbum))
            ?: return null
        val prefix = request.trackNumber?.let { "%02d ".format(it) } ?: ""
        val fileName = "$prefix${SafTree.sanitize(request.title)}.$suffix"
        val target = SafTree.createFile(
            applicationContext,
            tree,
            parent,
            fileName,
            mimeType,
        ) ?: return null
        applicationContext.contentResolver.openOutputStream(target, "wt").use { out ->
            if (out == null) return null
            out.write(bytes)
            out.flush()
        }
        return target
    }

    /**
     * Adds the new file to the index straight away.
     *
     * The values come from the tags just written, so this agrees with what a later rescan
     * would find - it only saves the user waiting for one.
     */
    private suspend fun recordInIndex(
        uri: android.net.Uri,
        request: DownloadRequest,
        size: Int,
    ) {
        val repo = MusicBrainzRepository.get(applicationContext)
        repo.upsertAll(
            listOf(
                LocalTrack(
                    documentUri = uri.toString(),
                    fileName = uri.lastPathSegment.orEmpty(),
                    size = size.toLong(),
                    lastModified = System.currentTimeMillis(),
                    recordingId = request.recordingId,
                    releaseId = request.releaseId,
                    releaseTrackId = request.releaseTrackId,
                    title = request.title,
                    artist = request.artist,
                    album = request.album,
                    matchKey = MatchKeys.trackKey(request.artist, request.title),
                    albumKey = MatchKeys.albumKey(request.album, request.title),
                ),
            ),
        )
        LibraryScanner.loadCached(applicationContext)
    }

    private companion object {
        const val READ_BUFFER = 64 * 1024
        const val DEFAULT_BUFFER = 8 * 1024 * 1024
        const val PROGRESS_INTERVAL_MS = 250L
    }
}

private fun DownloadRequest.toVorbisTags(cover: ByteArray?, lyrics: String?) = VorbisTags(
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    date = date,
    trackNumber = trackNumber,
    trackTotal = trackTotal,
    discNumber = discNumber,
    lyrics = lyrics,
    recordingId = recordingId,
    releaseId = releaseId,
    releaseTrackId = releaseTrackId,
    coverArt = cover,
    coverIsPng = cover.isPng(),
)

/** PNG files start with an 8-byte signature whose second byte is `P`. */
private fun ByteArray?.isPng(): Boolean =
    this != null && size > 8 && this[1] == 'P'.code.toByte()

/**
 * Holds the last few covers fetched from the Cover Art Archive.
 *
 * An album is downloaded as one worker per track, and without this each of them would
 * pull the same several-hundred-kilobyte image again.
 */
private object CoverArtCache {
    private const val MAX_ENTRIES = 4
    private val lock = Mutex()
    private val entries = LinkedHashMap<String, ByteArray?>()

    suspend fun get(releaseId: String?, releaseGroupId: String?): ByteArray? {
        val cacheKey = releaseId ?: releaseGroupId ?: return null
        lock.withLock {
            if (entries.containsKey(cacheKey)) return entries[cacheKey]
        }
        val urls = listOfNotNull(
            releaseId?.let { CoverArt.release(it) },
            releaseGroupId?.let { CoverArt.releaseGroup(it) },
        )
        var image: ByteArray? = null
        for (url in urls) {
            image = runCatching {
                val response = NetworkClient.execute(url)
                response.bytes.takeIf { response.isSuccess && it.isNotEmpty() }
            }.getOrNull()
            if (image != null) break
        }
        lock.withLock {
            if (entries.size >= MAX_ENTRIES) {
                entries.keys.firstOrNull()?.let { entries.remove(it) }
            }
            entries[cacheKey] = image
        }
        return image
    }
}
