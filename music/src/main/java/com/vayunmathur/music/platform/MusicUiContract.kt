package com.vayunmathur.music.platform

import android.net.Uri
import androidx.media3.common.Player
import com.vayunmathur.music.data.Music

/**
 * The UI contract between [MusicViewModel] and the screens.
 *
 * Screens take a state value plus an actions interface rather than the ViewModel itself,
 * so they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, and the ViewModel implements the actions interface.
 */

/** Everything the songs list draws. */
data class SongsUiState(
    val songs: List<Music> = emptyList(),
    /** Row to highlight — the loaded track, but only while it is playing from this list. */
    val playingSongId: Long? = null,
)

/** Everything the album detail screen draws. */
data class AlbumDetailUiState(
    val albumId: Long = 0,
    val name: String = "",
    val artUri: Uri? = null,
    /** Artist name(s) for the album, shown under the title. */
    val artistName: String = "",
    /** The artist to open when the name is tapped, or null when there isn't a single one. */
    val artistId: Long? = null,
    /** Pre-formatted "year • n songs • duration" line (see `album_info_format`). */
    val info: String = "",
    val tracks: List<Music> = emptyList(),
    val playingSongId: Long? = null,
)

/**
 * The loaded track plus transport state, shared by the now-playing bar and the full
 * player screen. Absent (null) when the queue is empty.
 */
data class NowPlayingUiState(
    val title: String = "",
    val artist: String = "",
    /** Album name of the loaded track, empty when unknown. */
    val album: String = "",
    val artworkUri: Uri? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** The artist to open when the name is tapped, or null when the track isn't in the library. */
    val artistId: Long? = null,
    /** The album to open when tapped, or null when the track isn't in the library. */
    val albumId: Long? = null,
    /** Queue origin, as stored on the queue — see [PlaybackSource.parse]. */
    val sourceId: String? = null,
    /** Human-readable name of the queue origin, for the "Go to …" shortcut. */
    val sourceName: String? = null,
    /** Raw embedded lyrics (LRC or plain), empty when the file carries none. */
    val lyrics: String = "",
)

/**
 * Playback callbacks. Every method has a no-op default so a preview can render a screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs. The
 * signatures match [MusicViewModel]'s existing methods, which is why the ViewModel can
 * implement this directly rather than through an adapter.
 */
interface MusicActions {
    fun playSong(songs: List<Music>, startWithIndex: Int, sourceId: String? = null, sourceName: String? = null) {}
    fun playShuffled(songs: List<Music>, sourceId: String? = null, sourceName: String? = null) {}
    fun togglePlayPause() {}
    fun seekTo(pos: Long) {}
    fun skipNext() {}
    fun skipPrevious() {}
    fun toggleShuffle() {}
    fun toggleRepeat() {}

    companion object {
        val Noop: MusicActions = object : MusicActions {}
    }
}
