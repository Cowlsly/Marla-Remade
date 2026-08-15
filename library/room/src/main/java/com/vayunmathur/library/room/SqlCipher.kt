package com.vayunmathur.library.room

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.DatabaseMigrations
import com.vayunmathur.library.util.databases
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.FileInputStream

private var sqlCipherLoaded = false
fun loadSqlCipher() {
    if (sqlCipherLoaded) return
    try {
        System.loadLibrary("sqlcipher")
        sqlCipherLoaded = true
    } catch (e: UnsatisfiedLinkError) {
        e.printStackTrace()
    }
}

/**
 * Reified convenience wrapper. Prefer obtaining databases through a
 * [RoomRepository] subclass rather than calling this directly — a follow-up
 * change restricts direct construction so every database has a single owner.
 */
inline fun <reified T : RoomDatabase> Context.buildDatabase(
    migrations: List<Migration>? = null,
    encryptionPassword: String? = null,
    dbName: String = "passwords-db",
    useDeviceProtectedStorage: Boolean = false
): T = buildDatabase(
    T::class.java,
    migrations,
    encryptionPassword,
    dbName,
    useDeviceProtectedStorage,
)

/**
 * Non-reified core builder (cached by database class). Takes the [dbClass]
 * explicitly so it can be called from generic code (e.g. [RoomRepository]) that
 * only has a `Class`/`KClass`. The reified overload above delegates here.
 */
fun <T : RoomDatabase> Context.buildDatabase(
    dbClass: Class<T>,
    migrations: List<Migration>? = null,
    encryptionPassword: String? = null,
    dbName: String = "passwords-db",
    useDeviceProtectedStorage: Boolean = false
): T {
    synchronized(databases) {
        @Suppress("UNCHECKED_CAST")
        databases[dbClass.kotlin]?.let { return it as T }
        val db = openRoomDatabase(dbClass, migrations, encryptionPassword, dbName, useDeviceProtectedStorage)
        databases[dbClass.kotlin] = db
        return db
    }
}

/**
 * Builds a database instance **without** touching the process-wide cache. Intended
 * for one-shot/auxiliary opens where the cached instance can't be reused — e.g. a
 * one-time legacy-migration read of an old, differently-named file of the same
 * class. Callers own the returned instance and must `close()` it. Kept in the
 * library so app modules never call the Room builder directly.
 */
fun <T : RoomDatabase> Context.buildDatabaseUncached(
    dbClass: Class<T>,
    migrations: List<Migration>? = null,
    encryptionPassword: String? = null,
    dbName: String = "passwords-db",
    useDeviceProtectedStorage: Boolean = false
): T = openRoomDatabase(dbClass, migrations, encryptionPassword, dbName, useDeviceProtectedStorage)

/** Shared build logic for [buildDatabase] and [buildDatabaseUncached] (no caching). */
private fun <T : RoomDatabase> Context.openRoomDatabase(
    dbClass: Class<T>,
    migrations: List<Migration>?,
    encryptionPassword: String?,
    dbName: String,
    useDeviceProtectedStorage: Boolean,
): T {
    loadSqlCipher()

    // Resolve migrations: explicit arg wins; otherwise read from the
    // database's companion object if it implements [DatabaseMigrations].
    val resolvedMigrations: List<Migration> = migrations ?: run {
        val companionField = try {
            dbClass.getDeclaredField("Companion").apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            null
        }
        val companionInstance = companionField?.get(null)
        (companionInstance as? DatabaseMigrations)?.migrations ?: emptyList()
    }

    val targetContext = if (useDeviceProtectedStorage) {
        val deviceContext = this.createDeviceProtectedStorageContext()
        val sharedPrefsName = "secure_prefs" // Matches DatabaseHelper.sharedPrefsName

        if (!deviceContext.getDatabasePath(dbName).exists() && this.getDatabasePath(dbName).exists()) {
            deviceContext.moveDatabaseFrom(this, dbName)
        }
        if (deviceContext.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE).all.isEmpty() &&
            this.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE).all.isNotEmpty()) {
            deviceContext.moveSharedPreferencesFrom(this, sharedPrefsName)
        }
        deviceContext
    } else {
        this
    }

    var password = encryptionPassword
    if (password == null) {
        val helper = DatabaseHelper(targetContext)
        if (!helper.isKeyGenerated()) {
            helper.generateKey()
            val cipher = helper.getCipherForEncryption()
            password = helper.createAndStorePassphrase(cipher)
        } else {
            val cipher = helper.getCipherForDecryption()
            password = helper.decryptPassphrase(cipher)
        }
    }

    encryptExistingDatabase(targetContext, dbName, password)

    val builder = Room.databaseBuilder(
        targetContext,
        dbClass,
        dbName
    ).addMigrations(*resolvedMigrations.toTypedArray())

    builder.openHelperFactory(SupportOpenHelperFactory(password.toByteArray(Charsets.UTF_8)))

    // Force TRUNCATE (rollback-journal) mode instead of the default WAL. With the
    // net.zetetic SQLCipher SupportSQLiteOpenHelper, WAL breaks Room's
    // InvalidationTracker: a write marks the table dirty but the change
    // notification isn't dispatched until a *later* write forces a refresh, so
    // Flow-backed queries only update on the next unrelated DB write (observed as
    // list UIs lagging until the next background write). TRUNCATE restores prompt,
    // per-write invalidation for every RoomDatabase built through this helper.
    builder.setJournalMode(RoomDatabase.JournalMode.TRUNCATE)

    return builder.build()
}

fun encryptExistingDatabase(context: Context, dbName: String, password: String) {
    loadSqlCipher()
    val dbFile = context.getDatabasePath(dbName)
    if (!dbFile.exists() || dbFile.length() < 16) return

    val isEncrypted = try {
        FileInputStream(dbFile).use { fis ->
            val header = ByteArray(16)
            if (fis.read(header) != 16) {
                true
            } else {
                !header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.UTF_8))
            }
        }
    } catch (e: Exception) {
        true
    }

    if (isEncrypted) return

    // It's not encrypted. Let's encrypt it.
    val tempFile = context.getDatabasePath("${dbName}_temp")
    if (tempFile.exists()) tempFile.delete()
    tempFile.parentFile?.mkdirs()
    tempFile.createNewFile()

    try {
        val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            "",
            null,
            net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE,
            null
        )
        db.rawExecSQL("PRAGMA cipher_compatibility = 4")
        db.rawExecSQL("ATTACH DATABASE '${tempFile.absolutePath}' AS encrypted KEY '${password}'")
        db.rawExecSQL("SELECT sqlcipher_export('encrypted')")
        db.rawExecSQL("DETACH DATABASE encrypted")
        db.close()

        // Delete the original plain database and its journal/WAL files
        dbFile.delete()
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        File("${dbFile.path}-journal").delete()

        tempFile.renameTo(dbFile)
    } catch (e: net.zetetic.database.sqlcipher.SQLiteNotADatabaseException) {
        tempFile.delete()
    }
}
