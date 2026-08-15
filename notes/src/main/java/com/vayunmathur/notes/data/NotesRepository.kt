package com.vayunmathur.notes.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

class NotesRepository private constructor(context: Context) :
    RoomRepository<NoteDatabase>(context, NoteDatabase::class, DB_NAME) {

    private val dao get() = db.noteDao()

    val notes: Flow<List<Note>> get() = dao.getAllFlow()
    fun noteByIdFlow(id: Long): Flow<Note?> = dao.getByIdFlow(id)

    suspend fun getAll(): List<Note> = dao.getAll()
    suspend fun upsert(value: Note): Long = dao.upsert(value)
    suspend fun delete(value: Note): Int = dao.delete(value)
    suspend fun upsertAll(values: List<Note>) = dao.upsertAll(values)

    /**
     * One-time migration: reads notes from the legacy "passwords-db" file (the
     * app's former database name), then deletes it. Uses an uncached auxiliary
     * open so it is independent of the primary DB's lifecycle/order.
     */
    suspend fun readAndClearLegacyNotes(): List<Note> {
        if (!appContext.getDatabasePath(LEGACY_DB_NAME).exists()) return emptyList()
        val legacy = openAuxiliary(LEGACY_DB_NAME)
        return try {
            legacy.noteDao().getAll()
        } finally {
            legacy.close()
            appContext.deleteDatabase(LEGACY_DB_NAME)
        }
    }

    companion object {
        private const val LEGACY_DB_NAME = "passwords-db"

        @Volatile private var instance: NotesRepository? = null
        fun get(context: Context): NotesRepository =
            instance ?: synchronized(this) {
                instance ?: NotesRepository(context).also { instance = it }
            }
    }
}
