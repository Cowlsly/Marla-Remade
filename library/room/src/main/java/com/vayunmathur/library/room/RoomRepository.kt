package com.vayunmathur.library.room

import android.content.Context
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import kotlin.reflect.KClass

/**
 * Base class for the single owner of a [RoomDatabase].
 *
 * Every app should build its database exactly once, behind an application-scoped
 * singleton repository that subclasses this. Consumers (ViewModels, services,
 * workers, receivers, widgets, assistant intents) obtain data through the
 * repository instead of calling [buildDatabase] themselves — which keeps a single
 * source of truth, one DB lifetime, and one place that owns migrations/encryption.
 *
 * The [db] instance is created lazily on first access using the **application**
 * context (never an Activity/Service context), so it is safe to hold for the
 * process lifetime without leaking a component.
 *
 * Typical usage:
 * ```
 * class NotesRepository private constructor(context: Context) :
 *     RoomRepository<NoteDatabase>(context, NoteDatabase::class, DB_NAME) {
 *
 *     val notes: Flow<List<Note>> get() = db.noteDao().getAllFlow()
 *     suspend fun upsert(note: Note) = db.noteDao().upsert(note)
 *
 *     companion object {
 *         @Volatile private var instance: NotesRepository? = null
 *         fun get(context: Context): NotesRepository =
 *             instance ?: synchronized(this) {
 *                 instance ?: NotesRepository(context).also { instance = it }
 *             }
 *     }
 * }
 * ```
 */
abstract class RoomRepository<DB : RoomDatabase>(
    context: Context,
    private val dbClass: KClass<DB>,
    private val dbName: String = "passwords-db",
    private val encryptionPassword: String? = null,
    private val useDeviceProtectedStorage: Boolean = false,
    private val migrations: List<Migration>? = null,
) {
    /** Application context — never a component context, so [db] can live process-wide. */
    protected val appContext: Context = context.applicationContext

    /**
     * The single [RoomDatabase] instance owned by this repository. Built once on
     * first access (the underlying builder also caches by class, so repeated
     * access across repositories of the same DB returns the same instance).
     */
    protected val db: DB by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.buildDatabase(
            dbClass.java,
            migrations,
            encryptionPassword,
            dbName,
            useDeviceProtectedStorage,
        )
    }

    /**
     * Opens a separate, **uncached** instance of the same database class under a
     * different [name] — for one-shot needs like reading an old, differently-named
     * file during a one-time legacy migration. The caller owns the returned
     * instance and must [close][RoomDatabase.close] it. Kept here so app modules
     * never invoke the database builder directly.
     */
    protected fun openAuxiliary(
        name: String,
        useDeviceProtectedStorage: Boolean = false,
    ): DB = appContext.buildDatabaseUncached(
        dbClass.java,
        migrations,
        encryptionPassword,
        name,
        useDeviceProtectedStorage,
    )
}
