package com.vayunmathur.musicbrainz.network.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the shapes the self-hosted catalogue actually returns, through the same [Json]
 * the app uses.
 *
 * The catalogue mirrors WS/2 but not exactly, and a decode failure here does not look like a
 * decode failure to the user - it surfaces as "Could not load" on a screen that worked
 * yesterday. Neither the build nor a test of any other layer catches that, so the wire
 * shapes are pinned here.
 */
class MusicBrainzModelsTest {

    private inline fun <reified T> decode(json: String): T =
        MusicBrainzApi.json.decodeFromString<T>(json)

    /**
     * Every field in every model is declared with a default, so an empty object has to decode.
     * This is what lets the catalogue omit anything it does not hold.
     */
    @Test
    fun `every model decodes from an empty object`() {
        assertEquals("", decode<MbArtist>("{}").id)
        assertEquals("", decode<MbArtistRef>("{}").id)
        assertEquals("", decode<MbArtistCredit>("{}").name)
        assertEquals("", decode<MbReleaseGroup>("{}").title)
        assertEquals("", decode<MbRelease>("{}").title)
        assertEquals("", decode<MbReleaseSummary>("{}").title)
        assertEquals("", decode<MbRecording>("{}").title)
        assertEquals("", decode<MbRecordingRef>("{}").title)
        assertEquals("", decode<MbTrack>("{}").title)
        assertEquals(1, decode<MbMedium>("{}").position)
        assertEquals(0, decode<MbMediumSummary>("{}").trackCount)
        assertNull(decode<MbLifeSpan>("{}").begin)
        assertNull(decode<MbArea>("{}").name)
        assertTrue(decode<MbArtistSearch>("{}").artists.isEmpty())
        assertTrue(decode<MbReleaseGroupSearch>("{}").releaseGroups.isEmpty())
        assertTrue(decode<MbRecordingSearch>("{}").recordings.isEmpty())
        assertTrue(decode<MbReleaseGroupBrowse>("{}").releaseGroups.isEmpty())
        assertTrue(decode<MbReleaseBrowse>("{}").releases.isEmpty())
    }

    /**
     * The shape that matters: a release whose tracks carry no `id`, because the catalogue has
     * no per-track MBID to give. The recording id survives, which is what the matching and the
     * download key then run on.
     */
    @Test
    fun `decodes a release whose tracks have no track mbid`() {
        val release = decode<MbRelease>(
            """
            {
              "id": "rel-1",
              "title": "In Rainbows",
              "media": [
                {
                  "position": 1,
                  "track-count": 2,
                  "tracks": [
                    {
                      "title": "15 Step",
                      "position": 1,
                      "length": 237000,
                      "recording": { "id": "rec-1", "title": "15 Step" }
                    },
                    {
                      "title": "Bodysnatchers",
                      "position": 2,
                      "recording": { "id": "rec-2", "title": "Bodysnatchers" }
                    }
                  ]
                }
              ]
            }
            """,
        )
        val tracks = release.media.single().tracks
        assertEquals(2, tracks.size)
        assertEquals("", tracks[0].id)
        assertEquals("rec-1", tracks[0].recording?.id)
        assertEquals("15 Step", tracks[0].title)
        assertEquals(237000, tracks[0].length)
        assertEquals("rec-2", tracks[1].recording?.id)
        assertNull(tracks[1].length)
    }

    /**
     * A default only applies to a key that is absent, so an explicit `null` on a non-nullable
     * field would abort the whole decode and blank the screen over one unset value.
     * `coerceInputValues` is what stops that; `MbTrack.id` is the field most likely to arrive
     * this way, since it is the one the catalogue no longer has.
     */
    @Test
    fun `survives explicit nulls on non-nullable fields`() {
        val track = decode<MbTrack>(
            """{ "id": null, "title": "Nude", "position": null, "artist-credit": null }""",
        )
        assertEquals("", track.id)
        assertEquals("Nude", track.title)
        assertEquals(0, track.position)
        assertTrue(track.artistCredit.isEmpty())

        val release = decode<MbRelease>("""{ "id": null, "title": null, "media": null }""")
        assertEquals("", release.id)
        assertEquals("", release.title)
        assertTrue(release.media.isEmpty())

        val browse = decode<MbReleaseGroupBrowse>(
            """{ "release-group-count": null, "release-groups": null }""",
        )
        assertEquals(0, browse.count)
        assertTrue(browse.releaseGroups.isEmpty())
    }

    /**
     * The reason the setting above is not optional. Decoded with the configuration the shared
     * client uses, the very same payload fails - so "the field has a default" is not on its own
     * enough to make an omitted field safe, and a catalogue that nulls a field instead of
     * leaving it out would take a screen down.
     */
    @Test
    fun `the same payload fails without coercion`() {
        val strict = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        assertFailsWith<SerializationException> {
            strict.decodeFromString<MbTrack>("""{ "id": null, "title": "Nude" }""")
        }
        assertFailsWith<SerializationException> {
            strict.decodeFromString<MbRelease>("""{ "media": null }""")
        }
    }

    /** Fields the app does not draw must not stop it decoding the ones it does. */
    @Test
    fun `ignores fields the app does not declare`() {
        val artist = decode<MbArtist>(
            """{ "id": "a-1", "name": "Radiohead", "rating": { "value": 4.5 }, "tags": [] }""",
        )
        assertEquals("a-1", artist.id)
        assertEquals("Radiohead", artist.name)
    }

    /** The hyphenated WS/2 keys the models rename, which are easy to get wrong server-side. */
    @Test
    fun `maps the hyphenated wire keys`() {
        val group = decode<MbReleaseGroup>(
            """
            {
              "id": "rg-1",
              "title": "In Rainbows",
              "primary-type": "Album",
              "secondary-types": ["Live"],
              "first-release-date": "2007-10-10",
              "artist-credit": [{ "name": "Radiohead", "joinphrase": "" }]
            }
            """,
        )
        assertEquals("Album", group.primaryType)
        assertEquals(listOf("Live"), group.secondaryTypes)
        assertEquals("2007-10-10", group.firstReleaseDate)
        assertEquals("Radiohead", group.artistCredit.display())

        val browse = decode<MbReleaseBrowse>(
            """{ "release-count": 3, "releases": [{ "id": "r-1", "title": "T" }] }""",
        )
        assertEquals(3, browse.count)
        assertEquals("r-1", browse.releases.single().id)
    }
}
