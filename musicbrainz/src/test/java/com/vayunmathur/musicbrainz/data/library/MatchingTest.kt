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
}

