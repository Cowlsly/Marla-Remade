package com.vayunmathur.findfamily.data

import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DefaultConverters
import kotlinx.coroutines.flow.Flow


@Dao
interface LocationValueDao {
    @Query("SELECT * FROM LocationValue WHERE (userid, timestamp) IN ( SELECT userid, MAX(timestamp) FROM LocationValue GROUP BY userid )")
    fun getLatest(): Flow<List<LocationValue>>

    @Query("SELECT * FROM LocationValue WHERE userid = :userid")
    fun getByUseridFlow(userid: Long): Flow<List<LocationValue>>

    @Query("DELETE FROM LocationValue WHERE timestamp < :cutoffEpochSeconds")
    suspend fun deleteOlderThan(cutoffEpochSeconds: Long)

    @Upsert
    suspend fun upsert(value: LocationValue): Long

    @Upsert
    suspend fun upsertAll(values: List<LocationValue>)
}

@Dao
interface WaypointDao {
    @Query("SELECT * FROM Waypoint")
    fun getAllFlow(): Flow<List<Waypoint>>

    @Query("SELECT * FROM Waypoint WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Waypoint?>

    @Query("SELECT * FROM Waypoint")
    suspend fun getAll(): List<Waypoint>

    @Query("SELECT * FROM Waypoint WHERE id = :id")
    suspend fun get(id: Long): Waypoint

    @Upsert
    suspend fun upsert(value: Waypoint): Long

    @Delete
    suspend fun delete(value: Waypoint): Int
}

@Dao
interface UserDao {
    @Query("SELECT * FROM User")
    fun getAllFlow(): Flow<List<User>>

    @Query("SELECT * FROM User WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<User?>

    @Query("SELECT * FROM User WHERE id = :id")
    suspend fun getById(id: Long): User?

    @Query("SELECT * FROM User")
    suspend fun getAll(): List<User>

    @Upsert
    suspend fun upsert(value: User): Long

    @Upsert
    suspend fun upsertAll(values: List<User>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(values: List<User>)

    @Delete
    suspend fun delete(value: User): Int

    // --- Atomic helpers for the "Disable after / Enable after" feature ---

    /** Atomically set only the time-based auto-toggle deadline (Never = null). Also clears any
     * arrival trigger so the two auto-toggle modes stay mutually exclusive. */
    @Query("UPDATE User SET sharingAutoToggleAt = :atEpochSeconds, sharingAutoToggleWaypointId = NULL WHERE id = :id")
    suspend fun setSharingAutoToggleAt(id: Long, atEpochSeconds: Long?)

    /** Atomically set the arrival-based auto-toggle waypoint (Never = null). Also clears any
     * time-based deadline so the two auto-toggle modes stay mutually exclusive. */
    @Query("UPDATE User SET sharingAutoToggleWaypointId = :waypointId, sharingAutoToggleAt = NULL WHERE id = :id")
    suspend fun setSharingAutoToggleWaypointId(id: Long, waypointId: Long?)

    /** Atomically set sharing enabled AND clear any pending auto-toggle (manual toggle path). */
    @Query("UPDATE User SET sendingEnabled = :enabled, sharingAutoToggleAt = NULL, sharingAutoToggleWaypointId = NULL WHERE id = :id")
    suspend fun setSendingEnabledAndClearToggle(id: Long, enabled: Boolean)

    /**
     * Atomically flip sharing for all rows whose timer is due. The WHERE clause guards against
     * TOCTOU races: if the user manually cleared (NULL) or rescheduled to the future, the row
     * no longer matches and we won't accidentally disable/enable when they didn't intend it.
     * Returns number of rows flipped.
     */
    @Query("UPDATE User SET sendingEnabled = CASE WHEN sendingEnabled THEN 0 ELSE 1 END, sharingAutoToggleAt = NULL WHERE sharingAutoToggleAt IS NOT NULL AND sharingAutoToggleAt <= :nowEpochSeconds")
    suspend fun applyDueAutoToggles(nowEpochSeconds: Long): Int

    /**
     * Atomically flip sharing for every row whose arrival trigger points at a waypoint that
     * "Me" is currently inside ([insideWaypointIds]). The waypoint id in the WHERE clause guards
     * against TOCTOU races the same way the timer does above: if the user cleared or changed the
     * trigger, the row no longer matches. Clears the trigger on flip so it fires once per arrival.
     * Returns number of rows flipped.
     */
    @Query("UPDATE User SET sendingEnabled = CASE WHEN sendingEnabled THEN 0 ELSE 1 END, sharingAutoToggleWaypointId = NULL WHERE sharingAutoToggleWaypointId IS NOT NULL AND sharingAutoToggleWaypointId IN (:insideWaypointIds)")
    suspend fun applyDueArrivalToggles(insideWaypointIds: List<Long>): Int

    // --- Atomic partial updates to avoid heartbeat clobbering sharingAutoToggleAt / sendingEnabled ---

    @Query("UPDATE User SET locationName = :locationName, lastWaypointId = :lastWaypointId, lastLocationChangeTime = :lastLocationChangeTime WHERE id = :id")
    suspend fun updateLocationMeta(id: Long, locationName: String, lastWaypointId: Long?, lastLocationChangeTime: Long)

    @Query("UPDATE User SET encryptionKey = :encryptionKey WHERE id = :id")
    suspend fun setEncryptionKey(id: Long, encryptionKey: String)

    @Query("UPDATE User SET pqcEncryptionKey = :pqcEncryptionKey WHERE id = :id")
    suspend fun setPqcEncryptionKey(id: Long, pqcEncryptionKey: String)

    @Query("UPDATE User SET platform = :platform WHERE id = :id")
    suspend fun setPlatform(id: Long, platform: String)

    @Query("UPDATE User SET name = :name, photo = :photo WHERE id = :id")
    suspend fun updateContactNamePhoto(id: Long, name: String, photo: String?)
}

@Dao
interface TemporaryLinkDao {
    @Query("SELECT * FROM TemporaryLink")
    fun getAllFlow(): Flow<List<TemporaryLink>>

    @Query("SELECT * FROM TemporaryLink WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<TemporaryLink?>

    @Query("SELECT * FROM TemporaryLink")
    suspend fun getAll(): List<TemporaryLink>

    @Upsert
    suspend fun upsert(value: TemporaryLink): Long

    @Delete
    suspend fun delete(value: TemporaryLink): Int
}

@Database(entities = [User::class, Waypoint::class, LocationValue::class, TemporaryLink::class], version = 11, exportSchema = false)
@ColumnTypeConverters(DefaultConverters::class)
abstract class FFDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun waypointDao(): WaypointDao
    abstract fun locationValueDao(): LocationValueDao
    abstract fun temporaryLinkDao(): TemporaryLinkDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations: List<androidx.room3.migration.Migration> = listOf(
            androidx.room3.migration.Migration(1, 2) {
                it.execSQL("CREATE INDEX IF NOT EXISTS index_LocationValue_timestamp ON LocationValue (timestamp)")
            },
            androidx.room3.migration.Migration(2, 3) {
                it.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_LocationValue_userid_timestamp` " +
                        "ON `LocationValue` (`userid`, `timestamp`)"
                )
            },
            androidx.room3.migration.Migration(3, 4) {
                it.execSQL("ALTER TABLE `User` ADD COLUMN `lastWaypointId` INTEGER")
            },
            androidx.room3.migration.Migration(4, 5) {
                it.execSQL("ALTER TABLE `User` ADD COLUMN `platform` TEXT")
            },
            androidx.room3.migration.Migration(5, 6) {
                it.execSQL("ALTER TABLE `User` ADD COLUMN `sharingAutoToggleAt` INTEGER")
            },
            androidx.room3.migration.Migration(6, 7) {
                it.execSQL("ALTER TABLE `User` ADD COLUMN `pqcEncryptionKey` TEXT")
                it.execSQL("ALTER TABLE `TemporaryLink` ADD COLUMN `pqcPublicKey` TEXT")
                it.execSQL("ALTER TABLE `TemporaryLink` ADD COLUMN `pqcKey` TEXT")
            },
            // Two changes to TemporaryLink, both needing a table rebuild (SQLite cannot alter a
            // primary key or drop a NOT NULL column in place):
            //  - `id` stops being an AUTOINCREMENT rowid and becomes a caller-supplied globally
            //    unique value (see newTemporaryLinkId).
            //  - links are post-quantum only, so the RSA `key`/`publicKey` columns are dropped
            //    and the PQC pair becomes NOT NULL.
            // Rows keep their existing ids on purpose — those URLs are already shared, and every
            // link expires within a week. Rows with no PQC bundle are dropped rather than
            // carried over: under PQC-only they can never publish again, so keeping them would
            // just leave dead entries in the list.
            androidx.room3.migration.Migration(7, 8) {
                it.execSQL(
                    "CREATE TABLE IF NOT EXISTS `TemporaryLink_new` (" +
                        "`name` TEXT NOT NULL, `deleteAt` INTEGER NOT NULL, " +
                        "`pqcPublicKey` TEXT NOT NULL, `pqcKey` TEXT NOT NULL, " +
                        "`id` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                it.execSQL(
                    "INSERT INTO `TemporaryLink_new` " +
                        "(`name`, `deleteAt`, `pqcPublicKey`, `pqcKey`, `id`) " +
                        "SELECT `name`, `deleteAt`, `pqcPublicKey`, `pqcKey`, `id` " +
                        "FROM `TemporaryLink` " +
                        "WHERE `pqcPublicKey` IS NOT NULL AND `pqcKey` IS NOT NULL"
                )
                it.execSQL("DROP TABLE `TemporaryLink`")
                it.execSQL("ALTER TABLE `TemporaryLink_new` RENAME TO `TemporaryLink`")
            },
            androidx.room3.migration.Migration(8, 9) {
                it.execSQL("ALTER TABLE `User` ADD COLUMN `sharingAutoToggleWaypointId` INTEGER")
            },
            // Custom UWB tracker support (DEV_BUILD): a `User` can now be a person or a
            // tracker. Room stores the enum as its name; existing rows default to PERSON.
            androidx.room3.migration.Migration(9, 10) {
                it.execSQL("ALTER TABLE `User` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'PERSON'")
            },
            // Share links carry a 32-byte ML-KEM seed (`pqcSeed`) instead of a full private
            // bundle, so `pqcKey` becomes nullable and only legacy rows keep it. SQLite cannot
            // drop NOT NULL in place, so rebuild the table as in Migration(7, 8). Rows keep
            // their ids — those URLs are already shared.
            androidx.room3.migration.Migration(10, 11) {
                it.execSQL(
                    "CREATE TABLE IF NOT EXISTS `TemporaryLink_new` (" +
                        "`name` TEXT NOT NULL, `deleteAt` INTEGER NOT NULL, " +
                        "`pqcPublicKey` TEXT NOT NULL, `pqcKey` TEXT, `pqcSeed` TEXT, " +
                        "`id` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                it.execSQL(
                    "INSERT INTO `TemporaryLink_new` " +
                        "(`name`, `deleteAt`, `pqcPublicKey`, `pqcKey`, `pqcSeed`, `id`) " +
                        "SELECT `name`, `deleteAt`, `pqcPublicKey`, `pqcKey`, NULL, `id` " +
                        "FROM `TemporaryLink`"
                )
                it.execSQL("DROP TABLE `TemporaryLink`")
                it.execSQL("ALTER TABLE `TemporaryLink_new` RENAME TO `TemporaryLink`")
            }
        )
    }
}
