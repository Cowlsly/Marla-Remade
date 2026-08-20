package com.vayunmathur.musicbrainz.network.api

import com.vayunmathur.library.network.NetworkClient
import java.net.URLEncoder

/**
 * Client for the Tidal v1 API.
 *
 * Every call needs a bearer token and the account's country code, so both are passed in
 * rather than held here - the token can be refreshed between calls, and the object stays
 * stateless like [MusicBrainzApi]. Unlike MusicBrainz, Tidal publishes no per-client rate
 * limit, so there is no gate.
 */
object TidalApi {

    private const val BASE = "https://api.tidal.com/v1"

    private fun headers(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/json",
    )

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    suspend fun search(query: String, token: String, countryCode: String, limit: Int = 25): TidalSearchResult =
        NetworkClient.getJson(
            "$BASE/search?query=${encode(query)}&countryCode=$countryCode&limit=$limit",
            headers = headers(token),
        )

    /**
     * The playback manifest for one track at [quality] (Tidal's `audioquality` value).
     * `playbackinfopostpaywall` is the endpoint that returns real stream URLs for a
     * subscriber; Tidal downgrades server-side when the track or plan cannot serve the ask.
     */
    suspend fun playbackInfo(
        trackId: Int,
        quality: String,
        token: String,
        countryCode: String,
    ): TidalPlaybackInfo = NetworkClient.getJson(
        "$BASE/tracks/$trackId/playbackinfopostpaywall" +
            "?audioquality=$quality&playbackmode=STREAM&assetpresentation=FULL" +
            "&countryCode=$countryCode",
        headers = headers(token),
    )
}
