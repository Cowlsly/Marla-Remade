package com.vayunmathur.musicbrainz.network.api

import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder

/**
 * The server has a catalogue to serve, but not yet: either the data pack was never
 * imported or an import is still running. The server signals both as HTTP 503 with an
 * `{"error":"not_ready"}` body, and reserves other statuses for genuine faults.
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
     * `coerceInputValues` makes an explicit `null` fall back to the declared default instead
     * of aborting the decode. Every field in the wire models has a default, but a default only
     * applies to a key that is ABSENT - a `null` on one of the non-nullable fields would
     * otherwise turn one unset value into a blank screen.
     *
     * The server guarantees it never emits `null` anywhere, enforced on its side by
     * `skip_serializing_if` plus a test, so this is defence in depth rather than something the
     * current contract relies on. It is here because the failure it prevents is invisible
     * until it happens against the live server, and `MbTrack.id` - the field the catalogue
     * stopped carrying - is the one most likely to arrive that way if the guarantee ever slips.
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
     * The failure for a non-2xx status, or null when the response is fine.
     *
     * 503 is the server saying it has no catalogue loaded yet, which is a wait rather than a
     * fault. The server uses 503 EXCLUSIVELY for that and 500 exclusively for a real failure,
     * with no overlap, so the status alone is enough to tell them apart - the body is carried
     * for the log, not parsed to make the decision.
     *
     * Split out from [get] so `MusicBrainzApiErrorTest` can pin the mapping without a server;
     * it is the one place the contract with the server is encoded.
     */
    internal fun failureFor(status: Int, body: String): IOException? = when {
        status == HttpURLConnection.HTTP_UNAVAILABLE ->
            CatalogueNotReadyException("HTTP 503: ${body.take(200)}")
        status in 200..299 -> null
        else -> IOException("HTTP $status: ${body.take(500)}")
    }

    private suspend inline fun <reified T> get(path: String): T {
        val response = NetworkClient.performRequest("$BASE/$path", headers = headers)
        failureFor(response.status, response.body)?.let { throw it }
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
