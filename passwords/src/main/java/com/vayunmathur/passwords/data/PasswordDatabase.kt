package com.vayunmathur.passwords.data

import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DatabaseMigrations
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PasswordDao {
    @Query("SELECT * FROM Password")
    abstract fun getAllFlow(): Flow<List<Password>>

    @Query("SELECT * FROM Password")
    abstract suspend fun getAll(): List<Password>

    @Query("SELECT * FROM Password WHERE id = :id")
    abstract fun getByIdFlow(id: Long): Flow<Password?>

    @Query("SELECT * FROM Password WHERE id = :id")
    abstract suspend fun getById(id: Long): Password?

    /** Writes [value] verbatim. Only the kdbx sync uses this, to preserve a pulled timestamp. */
    @Upsert
    abstract suspend fun upsertRaw(value: Password): Long

    @Delete
    abstract suspend fun delete(value: Password): Int

    suspend fun upsert(value: Password): Long =
        upsertRaw(value.copy(updatedAt = System.currentTimeMillis()))
}

@Dao
abstract class PasskeyDao {
    @Query("SELECT * FROM Passkey")
    abstract fun getAllFlow(): Flow<List<Passkey>>

    @Query("SELECT * FROM Passkey")
    abstract suspend fun getAll(): List<Passkey>

    @Query("SELECT * FROM Passkey WHERE rpId = :rpId")
    abstract suspend fun getByRpId(rpId: String): List<Passkey>

    @Query("SELECT * FROM Passkey WHERE credentialId = :credentialId")
    abstract suspend fun getByCredentialId(credentialId: String): Passkey?

    /** Writes [passkey] verbatim. Only the kdbx sync uses this, to preserve a pulled timestamp. */
    @Upsert
    abstract suspend fun upsertRaw(passkey: Passkey): Long

    @Delete
    abstract suspend fun delete(passkey: Passkey): Int

    suspend fun upsert(passkey: Passkey): Long =
        upsertRaw(passkey.copy(updatedAt = System.currentTimeMillis()))
}

@Database(
    entities = [Password::class, Passkey::class, SyncSnapshot::class],
    version = 4,
    exportSchema = false,
)
@ColumnTypeConverters(Converters::class)
abstract class PasswordDatabase : RoomDatabase() {
    abstract fun passwordDao(): PasswordDao
    abstract fun passkeyDao(): PasskeyDao
    abstract fun syncSnapshotDao(): SyncSnapshotDao

    companion object : DatabaseMigrations {
        override val migrations = listOf(
            Migration(1, 2) {
                it.execSQL(
                    """CREATE TABLE IF NOT EXISTS `Passkey` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `rpId` TEXT NOT NULL,
                        `rpName` TEXT NOT NULL,
                        `credentialId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `userName` TEXT NOT NULL,
                        `userDisplayName` TEXT NOT NULL,
                        `privateKeyBytes` BLOB NOT NULL,
                        `creationTime` INTEGER NOT NULL,
                        `lastUsedTime` INTEGER NOT NULL,
                        `signCount` INTEGER NOT NULL
                    )"""
                )
            },
            Migration(2, 3) {
                for (table in listOf("Password", "Passkey")) {
                    it.execSQL("ALTER TABLE `$table` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
                    it.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                    it.execSQL("UPDATE `$table` SET `syncId` = lower(hex(randomblob(16))) WHERE `syncId` = ''")
                }
                it.execSQL("UPDATE `Password` SET `updatedAt` = ${System.currentTimeMillis()} WHERE `updatedAt` = 0")
                it.execSQL("UPDATE `Passkey` SET `updatedAt` = `creationTime` WHERE `updatedAt` = 0")
                it.execSQL(
                    """CREATE TABLE IF NOT EXISTS `SyncSnapshot` (
                        `syncId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `contentHash` TEXT NOT NULL,
                        `localUpdatedAt` INTEGER NOT NULL,
                        `remoteModified` INTEGER NOT NULL,
                        `lastSyncedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`syncId`)
                    )"""
                )
            },
            // Split the single `userId` field into separate `username` and `email`, and add a
            // free-form `note`. SQLite can't rename/reorder columns in place, so recreate the
            // table; existing identities carry over as the username.
            Migration(3, 4) {
                it.execSQL(
                    """CREATE TABLE IF NOT EXISTS `Password_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `email` TEXT NOT NULL,
                        `password` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `totpSecret` TEXT,
                        `websites` TEXT NOT NULL,
                        `syncId` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )"""
                )
                it.execSQL(
                    """INSERT INTO `Password_new` (
                        `id`, `name`, `username`, `email`, `password`, `note`,
                        `totpSecret`, `websites`, `syncId`, `updatedAt`
                    )
                    SELECT `id`, `name`, `userId`, '', `password`, '',
                        `totpSecret`, `websites`, `syncId`, `updatedAt`
                    FROM `Password`"""
                )
                it.execSQL("DROP TABLE `Password`")
                it.execSQL("ALTER TABLE `Password_new` RENAME TO `Password`")
            },
        )
    }
}
