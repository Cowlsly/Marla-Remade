package com.vayunmathur.musicbrainz.network.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the MusicBrainz WS/2 JSON API.
 *
 * Only the fields the app actually draws are declared; the client decodes with
 * `ignoreUnknownKeys`, so the responses stay readable without mirroring the whole schema.
 */

@Serializable
data class MbArtistRef(
    val id: String = "",
    val name: String = "",
    val disambiguation: String? = null,
)

@Serializable
data class MbArtistCredit(
    val name: String = "",
    val joinphrase: String = "",
    val artist: MbArtistRef? = null,
)

/**
 * Flattens a credit list the way MusicBrainz intends it to be displayed, honouring the
 * join phrases so "A", " feat. ", "B" comes back as "A feat. B" rather than "A, B".
 */
fun List<MbArtistCredit>?.display(): String? {
    if (this.isNullOrEmpty()) return null
    return buildString {
        for (credit in this@display) {
            append(credit.name)
            append(credit.joinphrase)
        }
    }.trim().ifEmpty { null }
}

@Serializable
data class MbLifeSpan(
    val begin: String? = null,
    val end: String? = null,
    val ended: Boolean? = null,
)

@Serializable
data class MbArea(val name: String? = null)

@Serializable
data class MbArtist(
    val id: String = "",
    val name: String = "",
    val disambiguation: String? = null,
    val type: String? = null,
    val country: String? = null,
    val area: MbArea? = null,
    @SerialName("life-span") val lifeSpan: MbLifeSpan? = null,
)

@Serializable
data class MbReleaseGroup(
    val id: String = "",
    val title: String = "",
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
)

@Serializable
data class MbMediumSummary(
    val format: String? = null,
    @SerialName("track-count") val trackCount: Int = 0,
)

@Serializable
data class MbReleaseSummary(
    val id: String = "",
    val title: String = "",
    val status: String? = null,
    val date: String? = null,
    val country: String? = null,
    val disambiguation: String? = null,
    val media: List<MbMediumSummary> = emptyList(),
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    @SerialName("release-group") val releaseGroup: MbReleaseGroup? = null,
)

@Serializable
data class MbRecordingRef(
    val id: String = "",
    val title: String = "",
    val length: Int? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
)

@Serializable
data class MbTrack(
    val id: String = "",
    val title: String = "",
    val number: String? = null,
    val position: Int = 0,
    val length: Int? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val recording: MbRecordingRef? = null,
)

@Serializable
data class MbMedium(
    val position: Int = 1,
    val format: String? = null,
    val title: String? = null,
    @SerialName("track-count") val trackCount: Int = 0,
    val tracks: List<MbTrack> = emptyList(),
)

@Serializable
data class MbRelease(
    val id: String = "",
    val title: String = "",
    val status: String? = null,
    val date: String? = null,
    val country: String? = null,
    val disambiguation: String? = null,
    val media: List<MbMedium> = emptyList(),
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    @SerialName("release-group") val releaseGroup: MbReleaseGroup? = null,
)

@Serializable
data class MbRecording(
    val id: String = "",
    val title: String = "",
    val length: Int? = null,
    val disambiguation: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val releases: List<MbReleaseSummary> = emptyList(),
)

@Serializable
data class MbArtistSearch(
    val count: Int = 0,
    val artists: List<MbArtist> = emptyList(),
)

@Serializable
data class MbReleaseGroupSearch(
    val count: Int = 0,
    @SerialName("release-groups") val releaseGroups: List<MbReleaseGroup> = emptyList(),
)

@Serializable
data class MbRecordingSearch(
    val count: Int = 0,
    val recordings: List<MbRecording> = emptyList(),
)

@Serializable
data class MbReleaseGroupBrowse(
    @SerialName("release-group-count") val count: Int = 0,
    @SerialName("release-groups") val releaseGroups: List<MbReleaseGroup> = emptyList(),
)

@Serializable
data class MbReleaseBrowse(
    @SerialName("release-count") val count: Int = 0,
    val releases: List<MbReleaseSummary> = emptyList(),
)
