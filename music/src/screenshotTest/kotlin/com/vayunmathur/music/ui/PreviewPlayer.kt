package com.vayunmathur.music.ui

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.State
import androidx.media3.common.util.UnstableApi

/**
 * A fixed, non-playing [Player] for the store-listing previews.
 *
 * `MiniController` reads title, artist and transport state off a real [Player], and previews
 * have no media session to connect to.
 */
@OptIn(UnstableApi::class)
internal class PreviewPlayer(
    title: String,
    artist: String,
    durationMs: Long,
    positionMs: Long,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val metadata = MediaMetadata.Builder().setTitle(title).setArtist(artist).build()

    private val state = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaybackState(Player.STATE_READY)
        .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaylist(
            listOf(
                MediaItemData.Builder(title)
                    .setMediaItem(MediaItem.Builder().setMediaId(title).setMediaMetadata(metadata).build())
                    .setMediaMetadata(metadata)
                    .setDurationUs(durationMs * 1_000)
                    .build()
            )
        )
        .setContentPositionMs(positionMs)
        .build()

    override fun getState(): State = state
}
