package com.vayunmathur.musicbrainz.library

import android.content.Context
import androidx.core.net.toUri
import com.vayunmathur.musicbrainz.data.LocalTrack
import com.vayunmathur.musicbrainz.data.MusicBrainzRepository
import com.vayunmathur.musicbrainz.util.MusicBrainzPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Walks the user's music folder and records what is in it.
 *
 * Deliberately tag-driven rather than a ledger of what this app downloaded: the point is
 * to recognise music the user already had, however it got there.
 *
 * Files whose size and modification time are unchanged since the last scan are skipped
 * without being reopened, which is what keeps a rescan of a large library cheap.
 */
object LibraryScanner {

    suspend fun scan(context: Context): Int = withContext(Dispatchers.IO) {
        val treeUri = MusicBrainzPrefs(context).musicFolderUri() ?: return@withContext 0
        val repo = MusicBrainzRepository.get(context)

        LibraryIndex.setScanning(true)
        try {
            val known = repo.fingerprints().associateBy { it.documentUri }
            val files = ArrayList<DocEntry>()
            SafTree.walkFiles(context, treeUri.toUri()) { entry ->
                if (TagReader.isAudioFile(entry.name)) files.add(entry)
            }

            val seen = HashSet<String>(files.size)
            val updates = ArrayList<LocalTrack>()
            for (entry in files) {
                val key = entry.uri.toString()
                seen.add(key)
                val previous = known[key]
                if (previous != null &&
                    previous.size == entry.size &&
                    previous.lastModified == entry.lastModified
                ) {
                    continue
                }
                val tags = TagReader.read(context, entry.uri, entry.name)
                updates.add(
                    LocalTrack(
                        documentUri = key,
                        fileName = entry.name,
                        size = entry.size,
                        lastModified = entry.lastModified,
                        recordingId = tags.recordingId,
                        releaseId = tags.releaseId,
                        releaseTrackId = tags.releaseTrackId,
                        title = tags.title,
                        artist = tags.artist,
                        album = tags.album,
                        // Files with no usable tags still get a key from the filename, which
                        // is the only thing an untagged rip has to identify it.
                        matchKey = MatchKeys.trackKey(
                            tags.artist ?: tags.albumArtist,
                            tags.title ?: entry.name.substringBeforeLast('.'),
                        ),
                        albumKey = MatchKeys.albumKey(tags.album, tags.title),
                    ),
                )
                // Flushed in batches so an interrupted scan still leaves progress behind.
                if (updates.size >= BATCH_SIZE) {
                    repo.upsertAll(updates.toList())
                    updates.clear()
                }
            }

            if (updates.isNotEmpty()) repo.upsertAll(updates)

            val removed = known.keys - seen
            if (removed.isNotEmpty()) repo.deleteByUris(removed.toList())

            val all = repo.all()
            LibraryIndex.publish(all)
            all.size
        } finally {
            LibraryIndex.setScanning(false)
        }
    }

    /** Loads the last scan result so the browse screens have data before a rescan finishes. */
    suspend fun loadCached(context: Context) = withContext(Dispatchers.IO) {
        val repo = MusicBrainzRepository.get(context)
        LibraryIndex.publish(repo.all())
    }

    const val DB_NAME = "musicbrainz-db"
    private const val BATCH_SIZE = 200
}
