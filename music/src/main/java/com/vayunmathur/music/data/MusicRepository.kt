package com.vayunmathur.music.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import com.vayunmathur.library.util.ManyManyMatching
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for the music library database.
 *
 * Owns the one [MusicDatabase] instance (via [RoomRepository]) and is the
 * only place the five DAOs are touched. ViewModel, workers and assistant
 * intents all go through here.
 */
class MusicRepository private constructor(context: Context) :
    RoomRepository<MusicDatabase>(context, MusicDatabase::class) {

    private val musicDao get() = db.musicDao()
    private val albumDao get() = db.albumDao()
    private val artistDao get() = db.artistDao()
    private val playlistDao get() = db.playlistDao()
    private val matchingDao get() = db.matchingDao()

    // Flows (cold)
    val music: Flow<List<Music>> get() = musicDao.getAllFlow()
    val albums: Flow<List<Album>> get() = albumDao.getAllFlow()
    val artists: Flow<List<Artist>> get() = artistDao.getAllFlow()
    val playlists: Flow<List<Playlist>> get() = playlistDao.getAllFlow()
    val matchings: Flow<List<ManyManyMatching>> get() = matchingDao.flow()

    // Music
    suspend fun getAllMusic(): List<Music> = musicDao.getAll()
    suspend fun upsertAllMusic(items: List<Music>) = musicDao.upsertAll(items)
    suspend fun deleteAllMusic() = musicDao.deleteAll()
    suspend fun deleteMusicByIds(ids: List<Long>) = musicDao.deleteByIds(ids)

    // Album
    suspend fun getAllAlbums(): List<Album> = albumDao.getAll()
    suspend fun upsertAllAlbums(items: List<Album>) = albumDao.upsertAll(items)
    suspend fun deleteAllAlbums() = albumDao.deleteAll()

    // Artist
    suspend fun getAllArtists(): List<Artist> = artistDao.getAll()
    suspend fun upsertAllArtists(items: List<Artist>) = artistDao.upsertAll(items)
    suspend fun deleteAllArtists() = artistDao.deleteAll()

    // Playlist
    suspend fun getAllPlaylists(): List<Playlist> = playlistDao.getAll()
    suspend fun upsertPlaylist(value: Playlist): Long = playlistDao.upsert(value)
    suspend fun deletePlaylistById(id: Long) = playlistDao.deleteById(id)

    // Matching
    suspend fun getFromRight(rightId: Long, type: Int): List<Long> =
        matchingDao.getFromRight(rightId, type)

    suspend fun getFromLeft(leftId: Long, type: Int): List<Long> =
        matchingDao.getFromLeft(leftId, type)

    suspend fun upsertMatching(value: ManyManyMatching): Long = matchingDao.upsert(value)
    suspend fun upsertMatchings(values: List<ManyManyMatching>) = matchingDao.upsert(values)
    suspend fun deleteMatch(left: Long, right: Long, type: Int) =
        matchingDao.deleteMatch(left, right, type)
    suspend fun deleteFromRight(rightId: Long, type: Int) =
        matchingDao.deleteFromRight(rightId, type)
    suspend fun deleteFromLeft(leftId: Long, type: Int) =
        matchingDao.deleteFromLeft(leftId, type)
    suspend fun deleteByType(type: Int) = matchingDao.deleteByType(type)
    suspend fun clearMatchings() = matchingDao.clear()

    companion object {
        @Volatile private var instance: MusicRepository? = null
        fun get(context: Context): MusicRepository =
            instance ?: synchronized(this) {
                instance ?: MusicRepository(context).also { instance = it }
            }
    }
}
