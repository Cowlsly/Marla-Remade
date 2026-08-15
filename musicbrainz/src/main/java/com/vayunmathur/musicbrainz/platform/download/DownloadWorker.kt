package com.vayunmathur.musicbrainz.platform.download

import android.content.Context
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.musicbrainz.data.LocalTrack
import com.vayunmathur.musicbrainz.data.MusicBrainzRepository
import com.vayunmathur.musicbrainz.data.download.AudioResolver
import com.vayunmathur.musicbrainz.data.download.Lyrics
import com.vayunmathur.musicbrainz.data.download.Mp4Tagger
import com.vayunmathur.musicbrainz.data.download.Mp4Tags
import com.vayunmathur.musicbrainz.data.download.OggOpusTagger
import com.vayunmathur.musicbrainz.data.download.OpusRemuxer
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
            val audio = AudioResolver.resolve(
                artist = request.artist,
                title = request.title,
                album = request.album,
                durationMs = request.durationMs,
            ) ?: return@withContext fail(key, "No audio source found")

            DownloadQueue.update(DownloadState.Downloading, key)
            val raw = download(audio.url) { progress ->
                DownloadQueue.update(DownloadState.Downloading, key, progress)
            } ?: return@withContext fail(key, "Download failed")

            DownloadQueue.update(DownloadState.Tagging, key, 1f)
            val cover = if (prefs.embedCoverArt.first()) {
                CoverArtCache.get(request.releaseId, request.releaseGroupId)
            } else {
                null
            }
            val lyrics = if (prefs.fetchLyrics.first()) {
                Lyrics.fetch(request.artist, request.title, request.album, request.durationMs)
            } else {
                null
            }
            android.util.Log.i(
                "MBDownload",
                "tagging '${request.title}': needsRemux=${audio.needsRemux} suffix=${audio.suffix} " +
                    "bitrate=${audio.bitrate} raw=${raw.size} cover=${cover?.size ?: 0} " +
                    "lyrics=${lyrics?.length ?: 0}",
            )

            val tagged: ByteArray
            val suffix: String
            val mimeType: String
            when {
                audio.needsRemux -> {
                    val ogg = OpusRemuxer.remux(applicationContext, raw)
                    if (ogg != null) {
                        val startsOggS = ogg.size >= 4 &&
                            String(ogg, 0, 4, Charsets.ISO_8859_1) == "OggS"
                        val out = OggOpusTagger.tag(ogg, request.toVorbisTags(cover, lyrics))
                        android.util.Log.i(
                            "MBDownload",
                            "opus tag: oggStartsOggS=$startsOggS ogg=${ogg.size} " +
                                "taggedNull=${out == null} tagged=${out?.size ?: 0}",
                        )
                        tagged = out ?: ogg
                        suffix = "opus"
                        mimeType = "audio/ogg"
                    } else {
                        // Remuxing a valid Opus stream should not fail, but if the platform
                        // muxer refuses it the download is kept as the WebM it arrived as
                        // rather than lost. It carries no tags, but the scan finds it by name.
                        tagged = raw
                        suffix = "webm"
                        mimeType = "audio/webm"
                    }
                }
                audio.suffix == "m4a" -> {
                    tagged = Mp4Tagger.tag(raw, request.toMp4Tags(cover, lyrics)) ?: raw
                    suffix = "m4a"
                    mimeType = "audio/mp4"
                }
                else -> {
                    // Only MP4 and Ogg/Opus can be annotated here, so anything else is stored
                    // as fetched. It still gets found by the library scan, just on name and path.
                    tagged = raw
                    suffix = audio.suffix
                    mimeType = audio.mimeType
                }
            }
            android.util.Log.i("MBDownload", "writing '${request.title}' as .$suffix ($mimeType)")

            val written = writeToLibrary(treeUri, request, suffix, mimeType, tagged)
                ?: return@withContext fail(key, "Could not write to music folder")

            recordInIndex(written, request, tagged.size)
            DownloadQueue.update(DownloadState.Done, key, 1f)
            Result.success()
        } catch (e: Exception) {
            fail(key, e.message ?: "Download failed")
        }
    }

    private fun fail(key: String, message: String): Result {
        DownloadQueue.update(DownloadState.Failed, key, error = message)
        return Result.failure()
    }

    /**
     * Streams the audio into memory, reporting progress as it goes.
     *
     * Buffered rather than written straight to the destination because the tagger has to
     * rewrite the container before the file is filed away, and a partially written track
     * appearing in the user's music folder would be picked up by every other player on
     * the device.
     */
    private suspend fun download(url: String, onProgress: (Float) -> Unit): ByteArray? {
        val expected = NetworkClient.getContentLength(url) ?: 0L
        val buffer = ByteArrayOutputStream(if (expected > 0) expected.toInt() else DEFAULT_BUFFER)
        var lastReported = 0L
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
        if (!response.isSuccess && buffer.size() == 0) return null
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

private fun DownloadRequest.toMp4Tags(cover: ByteArray?, lyrics: String?) = Mp4Tags(
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    date = date,
    lyrics = lyrics,
    trackNumber = trackNumber,
    trackTotal = trackTotal,
    discNumber = discNumber,
    coverArt = cover,
    coverIsPng = cover.isPng(),
    freeform = buildMap {
        recordingId?.let { put("MusicBrainz Track Id", it) }
        releaseId?.let { put("MusicBrainz Album Id", it) }
        releaseTrackId?.let { put("MusicBrainz Release Track Id", it) }
    },
)

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
