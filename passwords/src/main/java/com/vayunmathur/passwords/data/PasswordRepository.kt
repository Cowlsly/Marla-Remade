package com.vayunmathur.passwords.data

import android.content.Context
import androidx.room.withTransaction
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single owner of the [PasswordDatabase] instance.
 *
 * Mirrors the shared [RoomRepository] pattern (see findfamily/FindFamilyRepository).
 * The DB is SQLCipher-encrypted. Its passphrase is managed by
 * [com.vayunmathur.library.util.DatabaseHelper] (key alias `db_no_auth_key`):
 * biometric unlock in [com.vayunmathur.passwords.MainActivity] decrypts the
 * biometric-bound key, then **mirrors** the same passphrase into the non-auth
 * helper via `DatabaseHelper.storePassphrase(passphrase)` so background
 * components (services / workers) can open it without biometrics.
 *
 * Before this repo every consumer called `buildDatabase<PasswordDatabase>()`
 * directly; most passed `encryptionPassword = null` (helper path), while
 * [com.vayunmathur.passwords.MainActivity] and one branch of
 * [com.vayunmathur.passwords.ui.PasskeyAuthActivity] passed the freshly-unlocked
 * `passphrase` explicitly. The underlying `SqlCipher.buildDatabase` caches by
 * `KClass` (`synchronized(databases)`), so the first builder won — and because
 * the explicit passphrase **equals** the mirrored helper passphrase, both paths
 * opened the same file. This repo preserves that semantic by delegating to the
 * helper (i.e. `encryptionPassword = null`): the lazy [db] is built on first
 * access via `DatabaseHelper.decryptPassphrase()` after `MainActivity` has
 * stored it, so every caller sees the identical database.
 */
class PasswordRepository private constructor(context: Context) :
    RoomRepository<PasswordDatabase>(context, PasswordDatabase::class) {

    private val passwordDao: PasswordDao get() = db.passwordDao()
    private val passkeyDao: PasskeyDao get() = db.passkeyDao()
    private val syncSnapshotDao: SyncSnapshotDao get() = db.syncSnapshotDao()

    // ------------------------------------------------------------------
    // Read flows (cold)
    // ------------------------------------------------------------------

    val passwords: Flow<List<Password>> get() = passwordDao.getAllFlow()
    val passkeys: Flow<List<Passkey>> get() = passkeyDao.getAllFlow()

    fun getPasswordByIdFlow(id: Long): Flow<Password?> = passwordDao.getByIdFlow(id)

    // ------------------------------------------------------------------
    // Password reads / writes
    // ------------------------------------------------------------------

    suspend fun getAllPasswords(): List<Password> = passwordDao.getAll()
    suspend fun getPasswordById(id: Long): Password? = passwordDao.getById(id)
    suspend fun upsertPassword(password: Password): Long = passwordDao.upsert(password)
    suspend fun upsertPasswordRaw(password: Password): Long = passwordDao.upsertRaw(password)
    suspend fun deletePassword(password: Password): Int = passwordDao.delete(password)

    // ------------------------------------------------------------------
    // Passkey reads / writes
    // ------------------------------------------------------------------

    suspend fun getAllPasskeys(): List<Passkey> = passkeyDao.getAll()
    suspend fun getPasskeysByRpId(rpId: String): List<Passkey> = passkeyDao.getByRpId(rpId)
    suspend fun getPasskeyByCredentialId(credentialId: String): Passkey? =
        passkeyDao.getByCredentialId(credentialId)

    suspend fun upsertPasskey(passkey: Passkey): Long = passkeyDao.upsert(passkey)
    suspend fun upsertPasskeyRaw(passkey: Passkey): Long = passkeyDao.upsertRaw(passkey)
    suspend fun deletePasskey(passkey: Passkey): Int = passkeyDao.delete(passkey)

    // ------------------------------------------------------------------
    // SyncSnapshot reads / writes
    // ------------------------------------------------------------------

    suspend fun getAllSnapshots(): List<SyncSnapshot> = syncSnapshotDao.getAll()
    suspend fun upsertSnapshot(snapshot: SyncSnapshot) = syncSnapshotDao.upsert(snapshot)
    suspend fun deleteSnapshotBySyncId(syncId: String) = syncSnapshotDao.deleteBySyncId(syncId)
    suspend fun deleteAllSnapshots() = syncSnapshotDao.deleteAll()

    // ------------------------------------------------------------------
    // Transaction
    // ------------------------------------------------------------------

    suspend fun <R> withTransaction(block: suspend () -> R): R = db.withTransaction(block)

    companion object {
        @Volatile
        private var instance: PasswordRepository? = null

        fun get(context: Context): PasswordRepository =
            instance ?: synchronized(this) {
                instance ?: PasswordRepository(context).also { instance = it }
            }
    }
}
