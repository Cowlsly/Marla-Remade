package com.vayunmathur.musicbrainz.data.download

import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
private data class LrcLibResponse(
    val duration: Double? = null,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
) {
    /** Synced lyrics are preferred; plain text is the fallback. Null when neither is set. */
    fun best(): String? =
        syncedLyrics?.takeIf { it.isNotBlank() } ?: plainLyrics?.takeIf { it.isNotBlank() }
}

/**
 * Fetches lyrics from LRCLIB.
 *
 * Synced lyrics are preferred so players that support them can follow along, with plain
 * text as the fallback. Failures are silent - lyrics are a nicety, and a missing set is
 * not a reason to fail a download.
 */
object Lyrics {

    private const val GET = "https://lrclib.net/api/get"
    private const val SEARCH = "https://lrclib.net/api/search"
    private val headers = mapOf(
        "User-Agent" to "ModernAppsMusicBrainz/1.0 ( https://ma.vayunmathur.com/apps/musicbrainz )",
    )

    suspend fun fetch(artist: String, title: String, album: String?, durationMs: Int?): String? =
        getExact(artist, title, album, durationMs) ?: search(artist, title, durationMs)

    /**
     * The exact-match endpoint. Fast and precise, but LRCLIB requires the duration to line
     * up within a couple of seconds, so a track whose audio length differs from the
     * MusicBrainz figure - common for YouTube sources - returns nothing here.
     */
    private suspend fun getExact(
        artist: String,
        title: String,
        album: String?,
        durationMs: Int?,
    ): String? {
        val url = buildString {
            append(GET)
            append("?artist_name=").append(encode(artist))
            append("&track_name=").append(encode(title))
            if (!album.isNullOrBlank()) append("&album_name=").append(encode(album))
            if (durationMs != null && durationMs > 0) append("&duration=").append(durationMs / 1000)
        }
        return try {
            NetworkClient.getJson<LrcLibResponse>(url, headers = headers).best()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The fuzzy fallback. It ignores duration, so it recovers the many tracks the exact
     * lookup misses; when a duration is known the candidate closest to it is chosen so a
     * different edit or a live version does not win over the album cut.
     */
    private suspend fun search(artist: String, title: String, durationMs: Int?): String? {
        val url = buildString {
            append(SEARCH)
            append("?artist_name=").append(encode(artist))
            append("&track_name=").append(encode(title))
        }
        val results = try {
            NetworkClient.getJson<List<LrcLibResponse>>(url, headers = headers)
        } catch (_: Exception) {
            return null
        }
        val withLyrics = results.filter { it.best() != null }
        if (withLyrics.isEmpty()) return null
        val target = durationMs?.takeIf { it > 0 }?.let { it / 1000.0 }
        val pick = if (target != null) {
            withLyrics.minByOrNull { abs((it.duration ?: Double.MAX_VALUE) - target) }
        } else {
            withLyrics.first()
        }
        return pick?.best()
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
