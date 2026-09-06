package com.vayunmathur.nowplaying.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "nowplaying-db"

@Dao
interface DetectionEventDao {
    @Query("SELECT * FROM DetectionEvent ORDER BY startedAt DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<DetectionEvent>>

    @Upsert
    suspend fun upsert(value: DetectionEvent): Long

    @Query("DELETE FROM DetectionEvent")
    suspend fun clear()
}

@Database(entities = [DetectionEvent::class], version = 1, exportSchema = false)
abstract class NowPlayingDatabase : RoomDatabase() {
    abstract fun detectionEventDao(): DetectionEventDao
}
