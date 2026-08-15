package com.vayunmathur.musicbrainz.network.api

import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder

/**
 * Client for the MusicBrainz WS/2 API.
 *
 * MusicBrainz allows one request per second per client and blocks callers that ignore
 * that, so every request funnels through [gate]. It also rejects requests without an
 * identifying User-Agent, which is why [USER_AGENT] names the app and links the source.
 */
object MusicBrainzApi {

    private const val BASE = "https://musicbrainz.org/ws/2"
    private const val USER_AGENT =
        "ModernAppsMusicBrainz/1.0 ( https://ma.vayunmathur.com/apps/musicbrainz )"
    private const val MIN_REQUEST_SPACING_MS = 1_100L

    private val rateLimit = Mutex()
    private var lastRequestAt = 0L

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
    )

    /** Serialises requests and spaces them out so the shared rate limit is respected. */
    private suspend fun <T> gate(block: suspend () -> T): T = rateLimit.withLock {
        val since = System.currentTimeMillis() - lastRequestAt
        if (since < MIN_REQUEST_SPACING_MS) delay(MIN_REQUEST_SPACING_MS - since)
        try {
            block()
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private suspend inline fun <reified T> get(path: String): T =
        gate { NetworkClient.getJson<T>("$BASE/$path", headers = headers) }

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
        get("release/$id?fmt=json&inc=recordings+artists+artist-credits+release-groups")

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
