package com.vayunmathur.music.platform

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.music.R
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.data.Playlist
import com.vayunmathur.music.data.TYPE_ALBUM_ARTIST
import com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Music app.
 *
 * Owns:
 *  - direct repo access for all four entities (Music, Album, Artist, Playlist)
 *  - playlist editing actions (create, rename, add track) routed through the repo
 *
 * Mirrors (does not duplicate the source of truth):
 *  - [PlaybackManager] state and playback actions.
 */
class MusicViewModel(
    application: Application,
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
) : AndroidViewModel(application), MusicActions {

    // --- Entity StateFlows ---
    val music: StateFlow<List<Music>> = repository.music
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val albums: StateFlow<List<Album>> = repository.albums
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val artists: StateFlow<List<Artist>> = repository.artists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playlists: StateFlow<List<Playlist>> = repository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val matchings: StateFlow<List<ManyManyMatching>> = repository.matchings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- PlaybackManager state mirror ---
    val isPlaying: StateFlow<Boolean> = playbackManager.isPlaying
    val currentPosition: StateFlow<Long> = playbackManager.currentPosition
    val duration: StateFlow<Long> = playbackManager.duration
    val shuffleMode: StateFlow<Boolean> = playbackManager.shuffleMode
    val repeatMode: StateFlow<Int> = playbackManager.repeatMode
    val currentMediaItem: StateFlow<MediaItem?> = playbackManager.currentMediaItem
    val currentSource: StateFlow<String?> = playbackManager.currentSource
    val currentSourceName: StateFlow<String?> = playbackManager.currentSourceName
    val player: StateFlow<Player?> = playbackManager.player

    override fun playSong(songs: List<Music>, startWithIndex: Int, sourceId: String?, sourceName: String?) =
        playbackManager.playSong(songs, startWithIndex, sourceId, sourceName)

    override fun playShuffled(songs: List<Music>, sourceId: String?, sourceName: String?) =
        playbackManager.playShuffled(songs, sourceId, sourceName)

    override fun togglePlayPause() { playbackManager.togglePlayPause() }
    override fun pausePlayback() { playbackManager.pause() }
    override fun seekTo(pos: Long) = playbackManager.seekTo(pos)
    override fun skipNext() { playbackManager.skipNext() }
    override fun skipPrevious() { playbackManager.skipPrevious() }
    override fun toggleShuffle() = playbackManager.toggleShuffle()
    override fun toggleRepeat() = playbackManager.toggleRepeat()

    @Composable
    fun nowPlayingState(): NowPlayingUiState? {
        val item by currentMediaItem.collectAsState()
        val playing by isPlaying.collectAsState()
        val position by currentPosition.collectAsState()
        val total by duration.collectAsState()
        val shuffle by shuffleMode.collectAsState()
        val repeat by repeatMode.collectAsState()
        val sourceId by currentSource.collectAsState()
        val sourceName by currentSourceName.collectAsState()
        val unknownTitle = stringResource(R.string.unknown_title)
        val unknownArtist = stringResource(R.string.unknown_artist)
        val metadata = item?.mediaMetadata ?: return null
        val songs by music.collectAsState()
        val song = item?.mediaId?.let { id -> songs.firstOrNull { it.id.toString() == id } }
        val songUri = song?.uri
        val application = getApplication<Application>()
        val lyrics by produceState("", songUri) {
            value = songUri?.let { uri ->
                withContext(Dispatchers.IO) { EmbeddedLyrics.read(application, uri.toUri()) }
            }.orEmpty()
        }
        return NowPlayingUiState(
            title = metadata.title?.toString() ?: unknownTitle,
            artist = metadata.artist?.toString() ?: unknownArtist,
            album = song?.album ?: metadata.albumTitle?.toString() ?: "",
            artworkUri = metadata.artworkUri,
            isPlaying = playing,
            positionMs = position,
            durationMs = total,
            shuffle = shuffle,
            repeatMode = repeat,
            artistId = song?.artistId,
            albumId = song?.albumId,
            sourceId = sourceId,
            sourceName = sourceName,
            lyrics = lyrics,
            song = song,
        )
    }

    @Composable
    fun playingSongIdFrom(sourceId: String): Long? {
        val item by currentMediaItem.collectAsState()
        val source by currentSource.collectAsState()
        return item?.mediaId?.toLongOrNull()?.takeIf { source == sourceId }
    }

    @Composable
    fun musicState(id: Long): State<Music?> {
        val list by music.collectAsState()
        return remember(id, list) { derivedStateOf { list.firstOrNull { it.id == id } } }
    }

    @Composable
    fun albumState(id: Long): State<Album?> {
        val list by albums.collectAsState()
        return remember(id, list) { derivedStateOf { list.firstOrNull { it.id == id } } }
    }

    @Composable
    fun artistState(id: Long): State<Artist?> {
        val list by artists.collectAsState()
        return remember(id, list) { derivedStateOf { list.firstOrNull { it.id == id } } }
    }

    @Composable
    fun playlistState(id: Long): State<Playlist?> {
        val list by playlists.collectAsState()
        return remember(id, list) { derivedStateOf { list.firstOrNull { it.id == id } } }
    }

    @Composable
    fun matchedAlbumsForArtist(artistId: Long): State<List<Long>> {
        val all by matchings.collectAsState()
        return remember(artistId, all) {
            derivedStateOf { all.filter { it.rightID == artistId && it.type == TYPE_ALBUM_ARTIST }.map { it.leftID } }
        }
    }

    @Composable
    fun matchedArtistsForAlbum(albumId: Long): State<List<Long>> {
        val all by matchings.collectAsState()
        return remember(albumId, all) {
            derivedStateOf { all.filter { it.leftID == albumId && it.type == TYPE_ALBUM_ARTIST }.map { it.rightID } }
        }
    }

    @Composable
    fun matchedMusicForPlaylist(playlistId: Long): State<List<Long>> {
        val all by matchings.collectAsState()
        return remember(playlistId, all) {
            derivedStateOf { all.filter { it.rightID == playlistId && it.type == TYPE_MUSIC_PLAYLIST }.map { it.leftID } }
        }
    }

    suspend fun getMusicInPlaylist(playlistId: Long): List<Long> =
        repository.getFromRight(playlistId, TYPE_MUSIC_PLAYLIST)

    fun createPlaylist(name: String, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = repository.upsertPlaylist(Playlist(name = name))
            onCreated(id)
        }
    }

    fun renamePlaylist(playlist: Playlist, newName: String) {
        viewModelScope.launch(Dispatchers.IO) { repository.upsertPlaylist(playlist.copy(name = newName)) }
    }

    fun deletePlaylist(playlist: Playlist, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteFromRight(playlist.id, TYPE_MUSIC_PLAYLIST)
            repository.deletePlaylistById(playlist.id)
            onDone()
        }
    }

    fun addMusicToPlaylist(playlistId: Long, musicId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.upsertMatching(ManyManyMatching(musicId, playlistId, TYPE_MUSIC_PLAYLIST))
            onDone()
        }
    }

    fun removeMusicFromPlaylist(playlistId: Long, musicId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteMatch(musicId, playlistId, TYPE_MUSIC_PLAYLIST)
            onDone()
        }
    }
}

/** Factory for constructing [MusicViewModel] with shared dependencies. */
class MusicViewModelFactory(
    private val application: Application,
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MusicViewModel::class.java)) { "Unexpected ViewModel class: $modelClass" }
        return MusicViewModel(application, repository, playbackManager) as T
    }
}
