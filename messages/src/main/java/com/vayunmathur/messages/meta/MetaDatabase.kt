package com.vayunmathur.messages.meta

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for Meta-specific data (Messenger/Instagram).
 * Stores sync state, thread metadata, and other platform-specific data.
 */
@Database(
    entities = [MetaThread::class, MetaSyncState::class],
    version = 1,
    exportSchema = false
)
abstract class MetaDatabase : RoomDatabase() {
    abstract fun threadDao(): MetaThreadDao
    abstract fun syncStateDao(): MetaSyncStateDao

    companion object {
        fun getDatabase(context: Context): MetaDatabase =
            MetaRepository.get(context).database()
    }
}
