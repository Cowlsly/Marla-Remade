package com.vayunmathur.nowplaying.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/** The single owner of [NowPlayingDatabase]; shared by the listener service and the UI. */
class DetectionHistory private constructor(context: Context) :
    RoomRepository<NowPlayingDatabase>(context, NowPlayingDatabase::class, DB_NAME) {

    private val dao get() = db.detectionEventDao()

    fun recent(limit: Int = RECENT_LIMIT): Flow<List<DetectionEvent>> = dao.recentFlow(limit)

    suspend fun upsert(event: DetectionEvent): Long = dao.upsert(event)

    suspend fun clear() = dao.clear()

    companion object {
        private const val RECENT_LIMIT = 200

        @Volatile private var instance: DetectionHistory? = null

        fun get(context: Context): DetectionHistory =
            instance ?: synchronized(this) {
                instance ?: DetectionHistory(context).also { instance = it }
            }
    }
}
