package com.vayunmathur.musicbrainz.data.download

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for the Tidal manifest decoder.
 *
 * The manifest is the one part of the Tidal path that is pure data transformation and so
 * can be pinned down off-device. A mistake here means either a download of the wrong number
 * of segments (a truncated or corrupt file) or the wrong extension (an untaggable file), so
 * both the BTS and DASH shapes and the codec-to-extension mapping are covered.
 */
class TidalManifestTest {

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `bts flac manifest yields the urls and a flac extension`() {
        val manifest = b64(
            """{"mimeType":"audio/flac","codecs":"flac","encryptionType":"NONE",""" +
                """"urls":["https://cdn.tidal.com/a","https://cdn.tidal.com/b"]}""",
        )
        val stream = TidalManifest.decode("application/vnd.tidal.bts", manifest, "LOSSLESS")
        assertEquals(listOf("https://cdn.tidal.com/a", "https://cdn.tidal.com/b"), stream.urls)
        assertEquals("flac", stream.suffix)
        assertEquals("audio/flac", stream.mimeType)
    }

    @Test
    fun `hi-res flac-codec manifest is filed as m4a`() {
        val manifest = b64(
            """{"mimeType":"audio/mp4","codecs":"flac","encryptionType":"NONE","urls":["https://cdn/1"]}""",
        )
        val stream = TidalManifest.decode("application/vnd.tidal.bts", manifest, "HI_RES_LOSSLESS")
        assertEquals("m4a", stream.suffix)
        assertEquals("audio/mp4", stream.mimeType)
    }

    @Test
    fun `bts aac manifest is filed as m4a`() {
        val manifest = b64(
            """{"mimeType":"audio/mp4","codecs":"mp4a.40.2","encryptionType":"NONE","urls":["https://cdn/1"]}""",
        )
        val stream = TidalManifest.decode("application/vnd.tidal.bts", manifest, "HIGH")
        assertEquals("m4a", stream.suffix)
    }

    @Test
    fun `dash manifest expands the segment timeline including repeats`() {
        val mpd = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet>
                  <Representation codecs="mp4a.40.2">
                    <SegmentTemplate media="https://cdn.tidal.com/seg_${'$'}Number${'$'}.mp4">
                      <SegmentTimeline>
                        <S d="100" r="2"/>
                        <S d="100"/>
                      </SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val stream = TidalManifest.decode("application/dash+xml", b64(mpd), "HI_RES_LOSSLESS")
        // S with r=2 is 3 segments, plus 1 = 4; $Number$ runs 0..4 inclusive = 5 urls.
        assertEquals(5, stream.urls.size)
        assertEquals("https://cdn.tidal.com/seg_0.mp4", stream.urls.first())
        assertEquals("https://cdn.tidal.com/seg_4.mp4", stream.urls.last())
        assertEquals("m4a", stream.suffix)
    }

    @Test
    fun `a protected dash manifest is rejected`() {
        val mpd = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet>
                  <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011"/>
                  <Representation codecs="mp4a.40.2">
                    <SegmentTemplate media="https://cdn/seg_${'$'}Number${'$'}.mp4">
                      <SegmentTimeline><S d="100"/></SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> {
            TidalManifest.decode("application/dash+xml", b64(mpd), "HI_RES_LOSSLESS")
        }
    }

    @Test
    fun `an encrypted manifest is rejected`() {
        val manifest = b64(
            """{"mimeType":"audio/flac","codecs":"flac","encryptionType":"OLD_AES","urls":["https://cdn/1"]}""",
        )
        assertFailsWith<IllegalArgumentException> {
            TidalManifest.decode("application/vnd.tidal.bts", manifest, "LOSSLESS")
        }
    }

    @Test
    fun `an unknown codec is rejected`() {
        val manifest = b64(
            """{"mimeType":"audio/x","codecs":"vorbis","encryptionType":"NONE","urls":["https://cdn/1"]}""",
        )
        assertFailsWith<IllegalArgumentException> {
            TidalManifest.decode("application/vnd.tidal.bts", manifest, "LOW")
        }
    }

    @Test
    fun `an unknown manifest type is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TidalManifest.decode("application/unknown", b64("{}"), "LOW")
        }
    }
}
