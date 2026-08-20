package com.vayunmathur.musicbrainz.data.download

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The Vorbis-comment metadata written into every download.
 *
 * A `.opus` file carries its metadata as a Vorbis comment list, with the cover art
 * base64-encoded into a `METADATA_BLOCK_PICTURE` comment wrapping a FLAC-style picture
 * block. The keys match what [com.vayunmathur.musicbrainz.data.library.TagReader] reads back,
 * so a download is recognised as owned on the next library scan.
 */
internal object VorbisComments {

    const val VENDOR = "ModernApps musicbrainz"

    /**
     * Builds the comment list: vendor string, a count, then each `KEY=value` entry, all with
     * little-endian length prefixes. This is the body of an Opus comment packet, after its
     * `OpusTags` magic.
     */
    fun buildCommentList(tags: VorbisTags): ByteArray {
        val out = ByteArrayOutputStream()
        val vendor = VENDOR.toByteArray(Charsets.UTF_8)
        out.write(intLe(vendor.size))
        out.write(vendor)

        val comments = ArrayList<ByteArray>()
        fun add(key: String, value: String?) {
            if (!value.isNullOrBlank()) {
                comments.add("$key=$value".toByteArray(Charsets.UTF_8))
            }
        }
        // Text tags first, so a reader with a bounded scan window sees the identifying
        // fields before the much larger cover-art comment.
        add("TITLE", tags.title)
        add("ARTIST", tags.artist)
        add("ALBUM", tags.album)
        add("ALBUMARTIST", tags.albumArtist)
        add("DATE", tags.date)
        tags.trackNumber?.let { add("TRACKNUMBER", it.toString()) }
        tags.trackTotal?.let { add("TRACKTOTAL", it.toString()) }
        tags.discNumber?.let { add("DISCNUMBER", it.toString()) }
        add("MUSICBRAINZ_TRACKID", tags.recordingId)
        add("MUSICBRAINZ_ALBUMID", tags.releaseId)
        add("MUSICBRAINZ_RELEASETRACKID", tags.releaseTrackId)
        add("LYRICS", tags.lyrics)
        tags.coverArt?.takeIf { it.isNotEmpty() }?.let {
            add(
                "METADATA_BLOCK_PICTURE",
                Base64.getEncoder().encodeToString(buildPictureBlock(it, tags.coverIsPng)),
            )
        }

        out.write(intLe(comments.size))
        for (comment in comments) {
            out.write(intLe(comment.size))
            out.write(comment)
        }
        return out.toByteArray()
    }

    /**
     * The FLAC PICTURE block body: a front-cover image with big-endian length fields. The
     * dimensions are left at zero, which players read from the image itself. Opus carries
     * this base64-encoded in a `METADATA_BLOCK_PICTURE` comment.
     */
    private fun buildPictureBlock(image: ByteArray, isPng: Boolean): ByteArray {
        val mime = (if (isPng) "image/png" else "image/jpeg").toByteArray(Charsets.ISO_8859_1)
        val block = ByteArrayOutputStream()
        block.write(intBe(3)) // picture type: front cover
        block.write(intBe(mime.size))
        block.write(mime)
        block.write(intBe(0)) // description length
        block.write(intBe(0)) // width
        block.write(intBe(0)) // height
        block.write(intBe(0)) // colour depth
        block.write(intBe(0)) // indexed colours
        block.write(intBe(image.size))
        block.write(image)
        return block.toByteArray()
    }

    fun intLe(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun intBe(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
