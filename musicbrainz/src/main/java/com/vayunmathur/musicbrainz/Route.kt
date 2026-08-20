package com.vayunmathur.musicbrainz

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Search : Route

    @Serializable
    data object Downloads : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object TidalLogin : Route

    @Serializable
    data class Artist(val artistId: String) : Route

    @Serializable
    data class ReleaseGroup(val releaseGroupId: String) : Route

    @Serializable
    data class Release(val releaseId: String) : Route
}
