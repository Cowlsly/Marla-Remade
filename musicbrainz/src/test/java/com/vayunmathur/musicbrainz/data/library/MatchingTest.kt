package com.vayunmathur.musicbrainz.data.library

import com.vayunmathur.musicbrainz.data.LocalTrack
import com.vayunmathur.musicbrainz.domain.library.MatchKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for how the app decides a track is already on device.
 *
 * The trade-off being pinned down here is which textual differences mean "same recording".
 * Too loose and the app tells the user they own a track they do not; too strict and every
 * file that was not tagged by Picard reports as missing, which is most of a real library.
 */
class MatchingTest {

    private fun localTrack(
        title: String,
        artist: String,
        album: String? = null,
        recordingId: String? = null,
        releaseId: String? = null,
        releaseTrackId: String? = null,
    ) = LocalTrack(
        documentUri = "content://test/$title",
        fileName = "$title.m4a",
        size = 1,
        lastModified = 1,
        recordingId = recordingId,
        releaseId = releaseId,
        releaseTrackId = releaseTrackId,
        title = title,
        artist = artist,
        album = album,
        matchKey = MatchKeys.trackKey(artist, title),
        albumKey = MatchKeys.albumKey(album, title),
    )

    // ------------------------------------------------------------------
    // Normalisation
    // ------------------------------------------------------------------

    @Test
    fun `normalises case punctuation and accents`() {
        assertEquals(MatchKeys.normalize("Björk"), MatchKeys.normalize("BJORK"))
        assertEquals(MatchKeys.normalize("Don't Look Back"), MatchKeys.normalize("Dont look back"))
        assertEquals(MatchKeys.normalize("Don't Look Back"), MatchKeys.normalize("Don\u2019t Look Back"))
        assertEquals(MatchKeys.normalize("Weird  Fishes"), MatchKeys.normalize("Weird Fishes"))
        assertEquals(MatchKeys.normalize("Café"), MatchKeys.normalize("cafe"))
        assertEquals(MatchKeys.normalize("Sit Down. Stand Up."), MatchKeys.normalize("Sit Down Stand Up"))
    }

    /** Reissue noise is not a different recording, so it is dropped. */
    @Test
    fun `drops reissue qualifiers`() {
        val plain = MatchKeys.normalize("Karma Police")
        assertEquals(plain, MatchKeys.normalize("Karma Police (Remastered)"))
        assertEquals(plain, MatchKeys.normalize("Karma Police [2017 Remaster]"))
        assertEquals(plain, MatchKeys.normalize("Karma Police (Album Version)"))
        assertEquals(plain, MatchKeys.normalize("Karma Police (Bonus Track)"))
    }

    /**
     * Live, remix and acoustic versions genuinely are different recordings, so they must
     * stay distinct - folding them in would mark tracks as owned that the user lacks.
     */
    @Test
    fun `keeps qualifiers that mean a different recording`() {
        val plain = MatchKeys.normalize("Karma Police")
        assertTrue(plain != MatchKeys.normalize("Karma Police (Live)"))
        assertTrue(plain != MatchKeys.normalize("Karma Police (Acoustic)"))
        assertTrue(plain != MatchKeys.normalize("Karma Police (Nightcore Remix)"))
    }

    @Test
    fun `takes the first credited artist so collaborations still match`() {
        assertEquals("Radiohead", MatchKeys.primaryArtist("Radiohead feat. Björk"))
        assertEquals("Radiohead", MatchKeys.primaryArtist("Radiohead & Thom Yorke"))
        assertEquals("Radiohead", MatchKeys.primaryArtist("Radiohead, Portishead"))
        assertEquals("Radiohead", MatchKeys.primaryArtist("Radiohead ft. Someone"))
        assertEquals("Radiohead", MatchKeys.primaryArtist("Radiohead"))
    }

    @Test
    fun `produces no key without both halves`() {
        assertNull(MatchKeys.trackKey(null, "Title"))
        assertNull(MatchKeys.trackKey("Artist", null))
        assertNull(MatchKeys.trackKey("", ""))
        assertNull(MatchKeys.normalize("   "))
    }

    // ------------------------------------------------------------------
    // Snapshot matching
    // ------------------------------------------------------------------

    @Test
    fun `matches on musicbrainz identifiers exactly`() {
        val snapshot = LibrarySnapshot.from(
            listOf(
                localTrack(
                    "Weird Fishes",
                    "Radiohead",
                    recordingId = "rec-1",
                    releaseId = "rel-1",
                    releaseTrackId = "trk-1",
                ),
            ),
        )
        assertTrue(snapshot.hasTrack("rec-1", null, null, null, null))
        assertTrue(snapshot.hasTrack(null, "trk-1", null, null, null))
        assertTrue(snapshot.hasRelease("rel-1"))
        assertFalse(snapshot.hasTrack("rec-other", null, null, null, null))
        assertFalse(snapshot.hasRelease("rel-other"))
    }

    /** The case that actually matters: files with no MusicBrainz tags at all. */
    @Test
    fun `falls back to artist and title for untagged files`() {
        val snapshot = LibrarySnapshot.from(
            listOf(localTrack("Weird Fishes", "Radiohead", album = "In Rainbows")),
        )
        assertTrue(
            snapshot.hasTrack(
                recordingId = "unknown-mbid",
                releaseTrackId = "unknown-mbid",
                artist = "Radiohead",
                album = "In Rainbows",
                title = "Weird Fishes",
            ),
        )
        // Same title, different artist, is a cover - not the same recording.
        assertFalse(
            snapshot.hasTrack(null, null, "Some Cover Band", "Tribute", "Weird Fishes"),
        )
    }

    @Test
    fun `matches across featured artist differences`() {
        val snapshot = LibrarySnapshot.from(listOf(localTrack("Song", "Artist A")))
        assertTrue(snapshot.hasTrack(null, null, "Artist A feat. Artist B", null, "Song"))
    }

    /**
     * On a compilation the file's artist is the performer while MusicBrainz may credit the
     * release to "Various Artists", so the album/title key is what closes the gap.
     */
    @Test
    fun `matches a compilation track on album and title`() {
        val snapshot = LibrarySnapshot.from(
            listOf(localTrack("Theme Song", "Some Performer", album = "Movie Soundtrack")),
        )
        assertTrue(
            snapshot.hasTrack(
                recordingId = null,
                releaseTrackId = null,
                artist = "Various Artists",
                album = "Movie Soundtrack",
                title = "Theme Song",
            ),
        )
    }

    @Test
    fun `an empty library owns nothing`() {
        assertFalse(LibrarySnapshot.Empty.hasTrack("rec", "trk", "Artist", "Album", "Title"))
        assertFalse(LibrarySnapshot.Empty.hasRelease("rel"))
        assertEquals(0, LibrarySnapshot.Empty.trackCount)
    }

    @Test
    fun `counts the tracks it was built from`() {
        val snapshot = LibrarySnapshot.from(
            listOf(
                localTrack("A", "Artist"),
                localTrack("B", "Artist"),
                localTrack("C", "Artist"),
            ),
        )
        assertEquals(3, snapshot.trackCount)
    }

    // ------------------------------------------------------------------
    // Matching without a release-track id
    // ------------------------------------------------------------------

    /**
     * The catalogue no longer carries a per-track MBID, so every browse row now asks with
     * `releaseTrackId = null`. A library scanned before that change still holds the id on its
     * rows, and those files must keep reporting as owned - the recording id is what carries
     * the match now, and it is exact.
     */
    @Test
    fun `a legacy row with a release track id still matches when the catalogue supplies none`() {
        val snapshot = LibrarySnapshot.from(
            listOf(
                localTrack(
                    "Weird Fishes",
                    "Radiohead",
                    album = "In Rainbows",
                    recordingId = "rec-1",
                    releaseTrackId = "legacy-trk-1",
                ),
            ),
        )
        assertTrue(
            snapshot.hasTrack(
                recordingId = "rec-1",
                releaseTrackId = null,
                artist = "Radiohead",
                album = "In Rainbows",
                title = "Weird Fishes",
            ),
        )
    }

    /**
     * The worse legacy case: a file whose only MusicBrainz id was the release-track one. With
     * nothing to compare it against, the text keys are the whole of the match, which is the
     * same path an untagged file takes.
     */
    @Test
    fun `a legacy row whose only id was the release track id still matches on text`() {
        val snapshot = LibrarySnapshot.from(
            listOf(
                localTrack(
                    "Weird Fishes",
                    "Radiohead",
                    album = "In Rainbows",
                    releaseTrackId = "legacy-trk-1",
                ),
            ),
        )
        assertTrue(
            snapshot.hasTrack(
                recordingId = null,
                releaseTrackId = null,
                artist = "Radiohead",
                album = "In Rainbows",
                title = "Weird Fishes",
            ),
        )
        // Still not a licence to match anything: a different recording must stay missing.
        assertFalse(
            snapshot.hasTrack(null, null, "Radiohead", "In Rainbows", "Videotape"),
        )
    }

    /** A file the app writes now: no release-track id on either side of the comparison. */
    @Test
    fun `a new row without a release track id matches on the recording id`() {
        val snapshot = LibrarySnapshot.from(
            listOf(
                localTrack(
                    "Reckoner",
                    "Radiohead",
                    album = "In Rainbows",
                    recordingId = "rec-2",
                    releaseId = "rel-2",
                    releaseTrackId = null,
                ),
            ),
        )
        assertTrue(snapshot.hasTrack("rec-2", null, null, null, null))
        assertTrue(snapshot.hasRelease("rel-2"))
        assertFalse(snapshot.hasTrack("rec-other", null, null, null, null))
    }

    /**
     * A real library after the switch is a mix: older files carry the release-track id, newer
     * ones do not. One snapshot has to answer for both, and a null on either side must never
     * be treated as a wildcard that matches everything.
     */
    @Test
    fun `a library holding both legacy and new rows matches each of them`() {
        val snapshot = LibrarySnapshot.from(
            listOf(
                localTrack(
                    "Nude",
                    "Radiohead",
                    album = "In Rainbows",
                    recordingId = "rec-legacy",
                    releaseTrackId = "legacy-trk",
                ),
                localTrack(
                    "Videotape",
                    "Radiohead",
                    album = "In Rainbows",
                    recordingId = "rec-new",
                    releaseTrackId = null,
                ),
            ),
        )
        assertTrue(snapshot.hasTrack("rec-legacy", null, null, null, null))
        assertTrue(snapshot.hasTrack("rec-new", null, null, null, null))
        // The legacy id still works for anything that does happen to know it.
        assertTrue(snapshot.hasTrack(null, "legacy-trk", null, null, null))
        // A null release-track id does not match the row that has one.
        assertFalse(snapshot.hasTrack(null, null, null, null, null))
        assertFalse(snapshot.hasTrack("rec-absent", null, "Radiohead", "In Rainbows", "Bloom"))
    }
}

