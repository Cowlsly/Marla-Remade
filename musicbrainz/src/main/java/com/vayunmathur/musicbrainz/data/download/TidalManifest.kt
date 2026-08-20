package com.vayunmathur.musicbrainz.data.download

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/** A decoded Tidal stream: the segment URLs in order, and the file extension to save under. */
data class TidalStream(
    val urls: List<String>,
    val suffix: String,
    val mimeType: String,
)

/**
 * Decodes a Tidal playback manifest into fetchable URLs.
 *
 * Ported from tiddl's `core/utils/parse.py`. Tidal returns the manifest base64-encoded in
 * one of two shapes, distinguished by the response's `manifestMimeType`:
 *
 * | Quality         | Container | manifestMimeType          |
 * | --------------- | --------- | ------------------------- |
 * | LOW / HIGH      | m4a (AAC) | application/vnd.tidal.bts |
 * | LOSSLESS        | flac      | application/vnd.tidal.bts |
 * | HI_RES_LOSSLESS | m4a       | application/dash+xml      |
 *
 * The BTS shape is a small JSON blob with the URLs already listed; DASH is an MPD whose
 * segment template has to be expanded into one URL per segment.
 *
 * A manifest whose `encryptionType` is anything other than `NONE` is rejected: the app has
 * no key to decrypt it, so the bytes would be unplayable, and writing them into the user's
 * music folder is worse than failing the download.
 */
object TidalManifest {

    private val DOLBY_CODECS = setOf("eac3", "ac4")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * @param audioQuality the quality Tidal actually served (its own value, e.g. `LOSSLESS`),
     *   which decides whether a FLAC-codec hi-res stream is filed as `.flac` or `.m4a`.
     * @throws IllegalArgumentException on an encrypted or unrecognised manifest.
     */
    fun decode(manifestMimeType: String, manifestBase64: String, audioQuality: String): TidalStream {
        val decoded = String(Base64.getDecoder().decode(manifestBase64), Charsets.UTF_8)
        return when (manifestMimeType) {
            "application/vnd.tidal.bts" -> decodeBts(decoded, audioQuality)
            "application/dash+xml" -> decodeDash(decoded, audioQuality)
            else -> throw IllegalArgumentException("Unknown Tidal manifest type: $manifestMimeType")
        }
    }

    private fun decodeBts(manifest: String, audioQuality: String): TidalStream {
        val root = json.parseToJsonElement(manifest) as JsonObject
        val encryption = (root["encryptionType"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull
        if (encryption != null && encryption != "NONE") {
            throw IllegalArgumentException("Tidal stream is encrypted ($encryption)")
        }
        val codecs = (root["codecs"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
        val urls = (root["urls"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        require(urls.isNotEmpty()) { "Tidal BTS manifest has no URLs" }
        return TidalStream(urls, suffixFor(codecs, audioQuality), mimeFor(codecs, audioQuality))
    }

    private fun decodeDash(manifest: String, audioQuality: String): TidalStream {
        val (codecs, urls) = parseMpd(manifest)
        require(urls.isNotEmpty()) { "Tidal DASH manifest has no segments" }
        return TidalStream(urls, suffixFor(codecs, audioQuality), mimeFor(codecs, audioQuality))
    }

    /**
     * Expands a Tidal MPD into segment URLs.
     *
     * Tidal's MPD has a single Representation with a SegmentTemplate: an initialization plus
     * a `media` URL containing `$Number$`, and a SegmentTimeline whose `S` elements each
     * describe one segment plus an optional `r` repeat count. The total is every `S` plus
     * its repeats, and `$Number$` runs `0..total` inclusive - the first index is the
     * initialization segment.
     */
    private fun parseMpd(xml: String): Pair<String, List<String>> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))

        val representation = doc.getElementsByTagName("Representation").item(0)
            ?: throw IllegalArgumentException("Tidal DASH manifest has no Representation")
        val codecs = representation.attributes?.getNamedItem("codecs")?.nodeValue.orEmpty()

        // Reject DRM the same way the BTS path rejects a non-NONE encryptionType: a protected
        // stream would download into an unplayable file, which is worse than failing.
        if (doc.getElementsByTagName("ContentProtection").length > 0) {
            throw IllegalArgumentException("Tidal DASH stream is protected")
        }

        val template = doc.getElementsByTagName("SegmentTemplate").item(0)
            ?: throw IllegalArgumentException("Tidal DASH manifest has no SegmentTemplate")
        val mediaTemplate = template.attributes?.getNamedItem("media")?.nodeValue
            ?: throw IllegalArgumentException("Tidal DASH manifest has no media template")

        val timeline = doc.getElementsByTagName("S")
        var total = 0
        for (i in 0 until timeline.length) {
            total += 1
            val repeat = timeline.item(i).attributes?.getNamedItem("r")?.nodeValue?.toIntOrNull()
            if (repeat != null) total += repeat
        }

        val urls = (0..total).map { mediaTemplate.replace("\$Number\$", it.toString()) }
        return codecs to urls
    }

    private fun suffixFor(codecs: String, audioQuality: String): String = when {
        codecs.equals("flac", ignoreCase = true) ->
            if (audioQuality == "HI_RES_LOSSLESS") "m4a" else "flac"
        codecs.startsWith("mp4", ignoreCase = true) -> "m4a"
        codecs.lowercase() in DOLBY_CODECS -> "m4a"
        else -> throw IllegalArgumentException("Unknown Tidal codecs: $codecs")
    }

    private fun mimeFor(codecs: String, audioQuality: String): String =
        when (suffixFor(codecs, audioQuality)) {
            "flac" -> "audio/flac"
            else -> "audio/mp4"
        }
}
