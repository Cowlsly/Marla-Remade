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

    private fun request(
        releaseTrackId: String?,
        recordingId: String?,
        releaseId: String? = RELEASE_ID,
    ) = DownloadRequest(
        recordingId = recordingId,
        releaseTrackId = releaseTrackId,
        releaseId = releaseId,
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
        val ids = listOf("trk-1" to "rec-1", null to "rec-1", "trk-1" to null, null to null)
        for (releaseId in listOf(RELEASE_ID, null)) {
            for ((releaseTrackId, recordingId) in ids) {
                assertEquals(
                    request(releaseTrackId, recordingId, releaseId).key,
                    trackRow(releaseTrackId, recordingId).downloadKey(releaseId, ALBUM),
                    "disagreed for releaseTrackId=$releaseTrackId " +
                        "recordingId=$recordingId releaseId=$releaseId",
                )
            }
        }
    }

    /**
     * The case the catalogue now produces: no track MBID, so the release and the recording
     * together carry the identity.
     */
    @Test
    fun `combines the release and recording when there is no release track id`() {
        assertEquals(
            "$RELEASE_ID\u0000rec-1",
            trackRow(null, "rec-1").downloadKey(RELEASE_ID, ALBUM),
        )
    }

    /**
     * The bug this formula exists to prevent. Before the release was folded in, the same
     * recording queued from two editions collapsed to one queue entry and one WorkManager
     * unique name, so the second download was dropped and the file that landed carried the
     * first release's album and track numbers.
     */
    @Test
    fun `keeps the same recording on two releases apart`() {
        val row = trackRow(null, "rec-1")
        assertNotEquals(
            row.downloadKey("release-first", ALBUM),
            row.downloadKey("release-second", ALBUM),
        )
        assertNotEquals(
            request(null, "rec-1", "release-first").key,
            request(null, "rec-1", "release-second").key,
        )
    }

    /** With no release either, the recording id alone still identifies it. */
    @Test
    fun `falls back to the recording id alone without a release`() {
        assertEquals("rec-1", trackRow(null, "rec-1").downloadKey(null, ALBUM))
    }

    /**
     * With neither id the key is the text triple. The album has to be part of it, or the same
     * song on two albums would collapse into one queue entry and one download would replace
     * the other.
     */
    @Test
    fun `distinguishes the same untagged song on two albums`() {
        val row = trackRow(null, null)
        assertEquals("$ARTIST\u0000$ALBUM\u0000$TITLE", row.downloadKey(RELEASE_ID, ALBUM))
        assertNotEquals(
            row.downloadKey(RELEASE_ID, ALBUM),
            row.downloadKey(RELEASE_ID, "A Different Album"),
        )
    }

    private companion object {
        const val TITLE = "Weird Fishes"
        const val ARTIST = "Radiohead"
        const val ALBUM = "In Rainbows"
        const val RELEASE_ID = "rel-1"
    }
}
