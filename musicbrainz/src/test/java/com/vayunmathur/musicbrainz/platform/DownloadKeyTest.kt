package com.vayunmathur.musicbrainz.platform

import com.vayunmathur.musicbrainz.platform.download.DownloadRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins [TrackRow.downloadKey] to [DownloadRequest.key].
 *
 * A release row looks its own download up by key, and the queue files it under the key the
 * request computed. The two formulas live in different files, so if they drift the progress
 * spinner and the cancel button simply stop finding anything - no crash, no failed build.
 *
 * This got sharper when the catalogue stopped carrying per-track MBIDs: the release-track id
 * used to be present on every row and made both sides agree trivially, and now the fallbacks
 * are what actually run.
 */
class DownloadKeyTest {

    private fun trackRow(releaseTrackId: String?, recordingId: String?) = TrackRow(
        rowKey = "0/0",
        mediumIndex = 0,
        releaseTrackId = releaseTrackId,
        recordingId = recordingId,
        position = 1,
        title = TITLE,
        artist = ARTIST,
    )

    private fun request(releaseTrackId: String?, recordingId: String?) = DownloadRequest(
        recordingId = recordingId,
        releaseTrackId = releaseTrackId,
        releaseId = null,
        releaseGroupId = null,
        title = TITLE,
        artist = ARTIST,
        album = ALBUM,
        albumArtist = ARTIST,
        date = null,
        trackNumber = 1,
        trackTotal = null,
        discNumber = null,
        durationMs = null,
    )

    @Test
    fun `agrees with the download request for every combination of ids`() {
        val combinations = listOf(
            "trk-1" to "rec-1",
            null to "rec-1",
            "trk-1" to null,
            null to null,
        )
        for ((releaseTrackId, recordingId) in combinations) {
            assertEquals(
                request(releaseTrackId, recordingId).key,
                trackRow(releaseTrackId, recordingId).downloadKey(ALBUM),
                "key disagreed for releaseTrackId=$releaseTrackId recordingId=$recordingId",
            )
        }
    }

    /** The case the catalogue now produces: no track MBID, so the recording id carries it. */
    @Test
    fun `falls back to the recording id when there is no release track id`() {
        assertEquals("rec-1", trackRow(null, "rec-1").downloadKey(ALBUM))
    }

    /**
     * With neither id the key is the text triple. The album has to be part of it, or the same
     * song on two albums would collapse into one queue entry and one download would replace
     * the other.
     */
    @Test
    fun `distinguishes the same untagged song on two albums`() {
        val row = trackRow(null, null)
        assertEquals("$ARTIST\u0000$ALBUM\u0000$TITLE", row.downloadKey(ALBUM))
        assertNotEquals(row.downloadKey(ALBUM), row.downloadKey("A Different Album"))
    }

    private companion object {
        const val TITLE = "Weird Fishes"
        const val ARTIST = "Radiohead"
        const val ALBUM = "In Rainbows"
    }
}
