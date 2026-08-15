package com.vayunmathur.musicbrainz.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import com.vayunmathur.musicbrainz.library.LibraryScanner

class MusicBrainzRepository private constructor(context: Context) :
    RoomRepository<MusicBrainzDatabase>(context, MusicBrainzDatabase::class, LibraryScanner.DB_NAME) {

    private val dao get() = db.localTrackDao()

    suspend fun upsertAll(tracks: List<LocalTrack>) = dao.upsertAll(tracks)
    suspend fun all(): List<LocalTrack> = dao.all()
    suspend fun fingerprints(): List<TrackFingerprint> = dao.fingerprints()
    suspend fun deleteByUris(uris: List<String>) = dao.deleteByUris(uris)
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun count(): Int = dao.count()

    companion object {
        @Volatile private var instance: MusicBrainzRepository? = null
        fun get(context: Context): MusicBrainzRepository =
            instance ?: synchronized(this) {
                instance ?: MusicBrainzRepository(context).also { instance = it }
            }
    }
}
