package com.vayunmathur.musicbrainz.network.api

import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URLEncoder

/**
 * Client for the MusicBrainz WS/2 API.
 *
 * MusicBrainz allows one request per second per client and blocks callers that ignore
 * that, so every request funnels through [gate]. It also rejects requests without an
 * identifying User-Agent, which is why [USER_AGENT] names the app and links the source.
 *
 * That one request per second is a hard ceiling, so the app's job is to spend it well:
 * deduplication and caching live above this object, in
 * [com.vayunmathur.musicbrainz.domain.cache.ResponseCache].
 */
object MusicBrainzApi {

    private const val BASE = "https://musicbrainz.org/ws/2"
    private const val USER_AGENT =
        "ModernAppsMusicBrainz/1.0 ( https://ma.vayunmathur.com/apps/musicbrainz )"

    /** A little over a second, so clock jitter cannot round two requests into one second. */
    internal const val MIN_REQUEST_SPACING_MS = 1_100L

    private val rateLimit = Mutex()
    private var lastRequestAt = 0L

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
     * WS/2 does emit `null` for absent values rather than omitting the key, so this is load
     * bearing rather than defensive. It can only turn a decode that would have thrown into one
     * that succeeds with the declared default, so it cannot change how a response that already
     * decodes is read.
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
     * Split out from [get] so `MusicBrainzApiErrorTest` can pin the mapping without a server.
     * WS/2 answers 503 when a client outruns the rate limit, which [gate] exists to prevent;
     * it is reported like any other failure because there is nothing extra for the user to do
     * about it.
     */
    internal fun failureFor(status: Int, body: String): IOException? =
        if (status in 200..299) null else IOException("HTTP $status: ${body.take(500)}")

    /**
     * How long to wait before sending, given when the last request was SENT.
     *
     * Pure and internal so `RateLimitTest` can pin the spacing rule without a clock or a
     * network.
     */
    internal fun waitBeforeSending(now: Long, sentAt: Long): Long =
        (MIN_REQUEST_SPACING_MS - (now - sentAt)).coerceAtLeast(0L)

    /** Serialises requests and spaces them out so the shared rate limit is respected. */
    private suspend fun <T> gate(block: suspend () -> T): T = rateLimit.withLock {
        delay(waitBeforeSending(System.currentTimeMillis(), lastRequestAt))
        // Stamped before the call rather than after it. The limit is on how often requests are
        // SENT, so the cooldown belongs alongside the round trip, not after it: stamping on
        // completion charged every caller the spacing PLUS the latency, which roughly doubled
        // the cost of each request and made a two-request screen take seconds.
        lastRequestAt = System.currentTimeMillis()
        block()
    }

    private suspend inline fun <reified T> get(path: String): T = gate {
        val response = NetworkClient.performRequest("$BASE/$path", headers = headers)
        failureFor(response.status, response.body)?.let { throw it }
        json.decodeFromString<T>(response.body)
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
