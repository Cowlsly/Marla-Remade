package com.vayunmathur.musicbrainz.network.api

import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder

/**
 * The server has a catalogue to serve, but not yet: either the data pack was never
 * imported or an import is still running.
 *
 * Kept apart from an ordinary failure because it resolves on its own, so the UI can tell
 * the user to come back shortly instead of reporting a fault they cannot act on.
 */
class CatalogueNotReadyException(message: String) : IOException(message)

/**
 * Client for the self-hosted MusicBrainz mirror.
 *
 * The mirror speaks WS/2's URL grammar and JSON shapes, so the requests below are the same
 * ones the public API takes. What it does not have is the public API's one-request-per-second
 * limit, so requests go straight out and callers are free to run them concurrently.
 */
object MusicBrainzApi {

    private const val BASE = "https://api.vayunmathur.com/api/mb/ws/2"

    /** Named so the mirror's own logs can tell this client apart from anything else. */
    private const val USER_AGENT =
        "ModernAppsMusicBrainz/1.0 ( https://ma.vayunmathur.com/apps/musicbrainz )"

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
    )

    /**
     * `coerceInputValues` is the load-bearing setting: every field in the wire models is
     * declared with a default, but a default only applies to a key that is absent. An
     * explicit `null` on one of the non-nullable fields would otherwise abort the whole
     * decode, turning one unset field into a blank screen.
     *
     * Internal rather than private so `MusicBrainzModelsTest` decodes through the real
     * configuration instead of a copy of it.
     */
    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * A 503 is the mirror saying it has no catalogue loaded yet, which is a wait rather
     * than a fault; every other non-2xx is a real failure.
     */
    private suspend inline fun <reified T> get(path: String): T {
        val response = NetworkClient.performRequest("$BASE/$path", headers = headers)
        if (response.status == HttpURLConnection.HTTP_UNAVAILABLE) {
            throw CatalogueNotReadyException("HTTP 503: ${response.body.take(200)}")
        }
        if (!response.isSuccess) {
            throw IOException("HTTP ${response.status}: ${response.body.take(500)}")
        }
        return json.decodeFromString<T>(response.body)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    suspend fun searchArtists(query: String, limit: Int = 25): List<MbArtist> =
        get<MbArtistSearch>("artist?query=${encode(query)}&fmt=json&limit=$limit").artists

    suspend fun searchReleaseGroups(query: String, limit: Int = 25): List<MbReleaseGroup> =
        get<MbReleaseGroupSearch>(
            "release-group?query=${encode(query)}&fmt=json&limit=$limit",
        ).releaseGroups

    suspend fun searchRecordings(query: String, limit: Int = 25): List<MbRecording> =
        get<MbRecordingSearch>("recording?query=${encode(query)}&fmt=json&limit=$limit").recordings

    suspend fun artist(id: String): MbArtist = get("artist/$id?fmt=json")

    /** An artist's discography. Browse rather than lookup, because lookup caps the list. */
    suspend fun releaseGroupsOfArtist(id: String, limit: Int = 100): List<MbReleaseGroup> =
        get<MbReleaseGroupBrowse>(
            "release-group?artist=$id&fmt=json&limit=$limit&offset=0",
        ).releaseGroups

    suspend fun releaseGroup(id: String): MbReleaseGroup =
        get("release-group/$id?fmt=json&inc=artist-credits")

    /** Every edition of a release group, so the user can pick the pressing they want. */
    suspend fun releasesOfReleaseGroup(id: String, limit: Int = 100): List<MbReleaseSummary> =
        get<MbReleaseBrowse>(
            "release?release-group=$id&fmt=json&inc=media+artist-credits&limit=$limit&offset=0",
        ).releases

    /**
     * A full release with its tracklist.
     *
     * `artist-credits` is what makes per-track artists available; without it a
     * compilation collapses to the release artist, which for soundtracks is usually
     * just "Various Artists".
     */
    suspend fun release(id: String): MbRelease =
        get("release/$id?fmt=json&inc=recordings+artists+artist-credits+release-groups+isrcs")

    suspend fun recording(id: String): MbRecording =
        get("recording/$id?fmt=json&inc=artists+artist-credits+releases")
}

/** Cover Art Archive image URLs. Both endpoints 404 when a release has no artwork. */
object CoverArt {
    fun release(id: String, size: Int = 500): String =
        "https://coverartarchive.org/release/$id/front-$size"

    fun releaseGroup(id: String, size: Int = 500): String =
        "https://coverartarchive.org/release-group/$id/front-$size"
}
