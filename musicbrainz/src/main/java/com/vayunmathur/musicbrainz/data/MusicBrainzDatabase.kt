package com.vayunmathur.musicbrainz.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert

/**
 * One audio file found in the user's music folder.
 *
 * Keyed by document URI so a rescan updates rows in place. [size] and [lastModified] are
 * kept so an unchanged file can be skipped on the next scan without reopening it.
 */
@Entity(tableName = "local_track")
data class LocalTrack(
    @PrimaryKey val documentUri: String,
    val fileName: String,
    val size: Long,
    val lastModified: Long,
    val recordingId: String? = null,
    val releaseId: String? = null,
    val releaseTrackId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Normalised "artist\u0000title", the fallback match for files with no MusicBrainz IDs. */
    val matchKey: String? = null,
    /** Normalised "album\u0000title", so compilations still match when the artist differs. */
    val albumKey: String? = null,
)

@Dao
interface LocalTrackDao {
    @Upsert
    suspend fun upsertAll(tracks: List<LocalTrack>)

    @Query("SELECT * FROM local_track")
    suspend fun all(): List<LocalTrack>

    @Query("SELECT documentUri, size, lastModified FROM local_track")
    suspend fun fingerprints(): List<TrackFingerprint>

    @Query("DELETE FROM local_track WHERE documentUri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    @Query("DELETE FROM local_track")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM local_track")
    suspend fun count(): Int
}

data class TrackFingerprint(
    val documentUri: String,
    val size: Long,
    val lastModified: Long,
)

@Database(entities = [LocalTrack::class], version = 1, exportSchema = false)
abstract class MusicBrainzDatabase : RoomDatabase() {
    abstract fun localTrackDao(): LocalTrackDao
}
