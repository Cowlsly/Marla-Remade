package com.vayunmathur.music

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route

    @Serializable
    data object Song : Route

    @Serializable
    data class AlbumDetail(val albumId: Long) : Route

    @Serializable
    data class ArtistDetail(val artistId: Long) : Route

    @Serializable
    data class PlaylistDetail(val playlistId: Long) : Route

    @Serializable
    data class AddToPlaylistDialog(val musicId: Long) : Route
}
