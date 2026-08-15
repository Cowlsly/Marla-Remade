package com.vayunmathur.messages.meta

import android.content.Context
import com.vayunmathur.library.room.RoomRepository

/**
 * Single owner of [MetaDatabase] (meta_database).
 *
 * The single `buildDatabase` call site now lives here (via [RoomRepository.db]);
 * [MetaDatabase.getDatabase] delegates to this repository so existing consumers
 * keep compiling unchanged. Unlike [com.vayunmathur.messages.data.MessagesRepository]
 * this DB's DAOs are currently unused at runtime — wrappers are still provided so
 * future persistence (e.g. offline inbox) goes through the repository.
 */
class MetaRepository private constructor(context: Context) :
    RoomRepository<MetaDatabase>(context, MetaDatabase::class, "meta_database") {

    private val threadDao get() = db.threadDao()
    private val syncStateDao get() = db.syncStateDao()

    // ------------------------------------------------------------------
    // MetaThreadDao wrappers
    // ------------------------------------------------------------------

    suspend fun getThread(threadId: String): MetaThread? = threadDao.getThread(threadId)

    suspend fun getThreadsForPlatform(platform: String): List<MetaThread> =
        threadDao.getThreadsForPlatform(platform)

    suspend fun insertThread(thread: MetaThread) = threadDao.insertThread(thread)

    suspend fun updateUnreadCount(threadId: String, count: Int) = threadDao.updateUnreadCount(threadId, count)

    suspend fun deleteThread(threadId: String) = threadDao.deleteThread(threadId)

    // ------------------------------------------------------------------
    // MetaSyncStateDao wrappers
    // ------------------------------------------------------------------

    suspend fun getSyncState(platform: String): MetaSyncState? = syncStateDao.getSyncState(platform)

    suspend fun insertSyncState(state: MetaSyncState) = syncStateDao.insertSyncState(state)

    /** Direct database access for legacy call sites; prefer the wrappers above for new code. */
    fun database(): MetaDatabase = db

    companion object {
        @Volatile
        private var instance: MetaRepository? = null

        fun get(context: Context): MetaRepository =
            instance ?: synchronized(this) {
                instance ?: MetaRepository(context).also { instance = it }
            }
    }
}
