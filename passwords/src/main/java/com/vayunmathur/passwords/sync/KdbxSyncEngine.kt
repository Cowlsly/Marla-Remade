package com.vayunmathur.passwords.sync

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordDao
import com.vayunmathur.passwords.data.PasswordDatabase
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.data.SyncSnapshot
import com.vayunmathur.passwords.data.SyncSnapshotDao
import com.vayunmathur.passwords.data.newSyncId
import com.vayunmathur.passwords.domain.KdbxNative
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

sealed interface KdbxSyncResult {
    data class Success(
        val pushed: Int,
        val pulled: Int,
        val deletedLocal: Int,
        val deletedRemote: Int,
    ) : KdbxSyncResult

    data object NotConfigured : KdbxSyncResult
    data object FileMissing : KdbxSyncResult
    data object WrongPassword : KdbxSyncResult
    data object VerifyFailed : KdbxSyncResult
    data class Error(val message: String) : KdbxSyncResult
}

/** Short machine-readable reason, stored in settings and rendered by the settings screen. */
fun KdbxSyncResult.errorCode(): String = when (this) {
    KdbxSyncResult.FileMissing -> "file_missing"
    KdbxSyncResult.WrongPassword -> "wrong_password"
    KdbxSyncResult.VerifyFailed -> "verify_failed"
    is KdbxSyncResult.Error -> message
    else -> ""
}

/** The kdbx file. Abstracted so the engine can be exercised without SAF. */
interface KdbxDocument {
    fun read(): ByteArray
    fun write(bytes: ByteArray)
}

/** kdbx (de)serialisation. Abstracted so the engine can be exercised without JNI. */
interface KdbxCodec {
    /** Returns null when the password is wrong or the payload is not a vault. */
    fun read(password: String, bytes: ByteArray): List<Map<String, String>>?
    fun write(password: String, entries: List<Map<String, String>>): ByteArray?
}

/**
 * Bidirectional sync between the encrypted Room database and one kdbx file.
 *
 * The whole merge is computed in memory. Nothing is mutated until the remote has been
 * read, decrypted, merged, re-encoded and verified — so a missing, unreadable or
 * wrongly-keyed file can never wipe the local vault.
 */
class KdbxSyncEngine(
    private val passwordDao: PasswordDao,
    private val passkeyDao: PasskeyDao,
    private val snapshotDao: SyncSnapshotDao,
    private val codec: KdbxCodec,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    constructor(db: PasswordDatabase, codec: KdbxCodec) : this(
        db.passwordDao(),
        db.passkeyDao(),
        db.syncSnapshotDao(),
        codec,
        { db.withTransaction(it) },
    )

    constructor(repository: PasswordRepository, codec: KdbxCodec) : this(
        passwordDao = object : PasswordDao() {
            override fun getAllFlow(): Flow<List<Password>> = repository.passwords
            override suspend fun getAll(): List<Password> = repository.getAllPasswords()
            override fun getByIdFlow(id: Long): Flow<Password?> = repository.getPasswordByIdFlow(id)
            override suspend fun getById(id: Long): Password? = repository.getPasswordById(id)
            override suspend fun upsertRaw(value: Password): Long = repository.upsertPasswordRaw(value)
            override suspend fun delete(value: Password): Int = repository.deletePassword(value)
        },
        passkeyDao = object : PasskeyDao() {
            override fun getAllFlow(): Flow<List<Passkey>> = repository.passkeys
            override suspend fun getAll(): List<Passkey> = repository.getAllPasskeys()
            override suspend fun getByRpId(rpId: String): List<Passkey> = repository.getPasskeysByRpId(rpId)
            override suspend fun getByCredentialId(credentialId: String): Passkey? =
                repository.getPasskeyByCredentialId(credentialId)
            override suspend fun upsertRaw(passkey: Passkey): Long = repository.upsertPasskeyRaw(passkey)
            override suspend fun delete(passkey: Passkey): Int = repository.deletePasskey(passkey)
        },
        snapshotDao = object : SyncSnapshotDao {
            override suspend fun getAll(): List<SyncSnapshot> = repository.getAllSnapshots()
            override suspend fun upsert(snapshot: SyncSnapshot) = repository.upsertSnapshot(snapshot)
            override suspend fun deleteBySyncId(syncId: String) = repository.deleteSnapshotBySyncId(syncId)
            override suspend fun deleteAll() = repository.deleteAllSnapshots()
        },
        codec = codec,
        inTransaction = { repository.withTransaction(it) },
    )

    private val passwordKind = object : EntityKind<Password>() {
        override val kind = SyncSnapshot.KIND_PASSWORD
        override fun syncId(e: Password) = e.syncId
        override fun updatedAt(e: Password) = e.updatedAt
        override fun rowId(e: Password) = e.id
        override fun fields(e: Password) = EntryMapper.toFields(e)
        override fun entity(fields: Map<String, String>) = EntryMapper.toPassword(fields)
        override fun contentKey(e: Password) = EntryMapper.contentKey(e)
        override fun rebind(e: Password, rowId: Long, syncId: String) = e.copy(id = rowId, syncId = syncId)
        override suspend fun persist(e: Password) { passwordDao.upsertRaw(e) }
        override suspend fun delete(e: Password) { passwordDao.delete(e) }
    }

    private val passkeyKind = object : EntityKind<Passkey>() {
        override val kind = SyncSnapshot.KIND_PASSKEY
        override fun syncId(e: Passkey) = e.syncId
        override fun updatedAt(e: Passkey) = e.updatedAt
        override fun rowId(e: Passkey) = e.id
        override fun fields(e: Passkey) = EntryMapper.toFields(e)
        override fun entity(fields: Map<String, String>) = EntryMapper.toPasskey(fields)
        override fun contentKey(e: Passkey) = EntryMapper.contentKey(e)
        override fun rebind(e: Passkey, rowId: Long, syncId: String) = e.copy(id = rowId, syncId = syncId)
        override suspend fun persist(e: Passkey) { passkeyDao.upsertRaw(e) }
        override suspend fun delete(e: Passkey) { passkeyDao.delete(e) }
    }

    suspend fun sync(
        document: KdbxDocument,
        vaultPassword: String,
        onBackup: (ByteArray) -> Unit = {},
    ): KdbxSyncResult {
        val remoteBytes = try {
            document.read()
        } catch (_: Exception) {
            return KdbxSyncResult.FileMissing
        }
        // A brand new SAF document is zero bytes; that is an empty vault, not a failure.
        val remoteEntries = if (remoteBytes.isEmpty()) {
            emptyList()
        } else {
            codec.read(vaultPassword, remoteBytes) ?: return KdbxSyncResult.WrongPassword
        }

        val now = System.currentTimeMillis()
        val baseline = snapshotDao.getAll().associateBy { it.syncId }
        val (remotePasskeys, remotePasswords) = remoteEntries.partition { EntryMapper.isPasskeyEntry(it) }

        val passwordPlan = merge(
            passwordKind,
            passwordDao.getAll(),
            remotePasswords,
            baseline.filterValues { it.kind == SyncSnapshot.KIND_PASSWORD },
            now,
        )
        val passkeyPlan = merge(
            passkeyKind,
            passkeyDao.getAll(),
            remotePasskeys,
            baseline.filterValues { it.kind == SyncSnapshot.KIND_PASSKEY },
            now,
        )

        if (passwordPlan.remoteChanged || passkeyPlan.remoteChanged) {
            val merged = passwordPlan.remoteFields + passkeyPlan.remoteFields
            val bytes = codec.write(vaultPassword, merged)
                ?: return KdbxSyncResult.Error("Failed to encode KDBX")
            // A SAF single-document write truncates, so prove the payload parses first.
            val verified = codec.read(vaultPassword, bytes)
            if (verified == null || verified.size != merged.size) return KdbxSyncResult.VerifyFailed
            if (remoteBytes.isNotEmpty()) onBackup(remoteBytes)
            try {
                document.write(bytes)
            } catch (e: Exception) {
                return KdbxSyncResult.Error(e.message ?: "Failed to write KDBX file")
            }
        }

        inTransaction {
            applyPlan(passwordKind, passwordPlan)
            applyPlan(passkeyKind, passkeyPlan)
        }

        return KdbxSyncResult.Success(
            pushed = passwordPlan.pushed + passkeyPlan.pushed,
            pulled = passwordPlan.pulled + passkeyPlan.pulled,
            deletedLocal = passwordPlan.deletedLocal + passkeyPlan.deletedLocal,
            deletedRemote = passwordPlan.deletedRemote + passkeyPlan.deletedRemote,
        )
    }

    private suspend fun <T> applyPlan(kind: EntityKind<T>, plan: Plan<T>) {
        for (entity in plan.localDeletes) kind.delete(entity)
        for (entity in plan.localUpserts) kind.persist(entity)
        for (syncId in plan.removedSyncIds) snapshotDao.deleteBySyncId(syncId)
        for (snapshot in plan.snapshots) snapshotDao.upsert(snapshot)
    }

    private fun <T> merge(
        kind: EntityKind<T>,
        localEntities: List<T>,
        remoteEntries: List<Map<String, String>>,
        baseline: Map<String, SyncSnapshot>,
        now: Long,
    ): Plan<T> {
        val localUpserts = mutableListOf<T>()
        val localDeletes = mutableListOf<T>()
        val remoteOut = mutableListOf<Map<String, String>>()
        val snapshots = mutableListOf<SyncSnapshot>()
        val removed = mutableListOf<String>()
        var remoteChanged = false
        var pushed = 0
        var pulled = 0
        var deletedLocal = 0
        var deletedRemote = 0

        // Rows written before the sync columns existed have no identity yet.
        val local = LinkedHashMap<String, T>()
        val freshlyIdentified = mutableSetOf<String>()
        for (raw in localEntities) {
            val entity = if (kind.syncId(raw).isBlank()) {
                kind.rebind(raw, kind.rowId(raw), newSyncId()).also { freshlyIdentified += kind.syncId(it) }
            } else {
                raw
            }
            local[kind.syncId(entity)] = entity
        }

        val remote = LinkedHashMap<String, Map<String, String>>()
        val unidentified = mutableListOf<Map<String, String>>()
        for (entry in remoteEntries) {
            val id = entry[EntryMapper.FIELD_SYNC_ID]?.takeIf { it.isNotBlank() }
            if (id != null && id !in remote) remote[id] = entry else unidentified += entry
        }

        // Entries written by another client carry no _SyncId. Adopt the identity of the
        // local row they describe, otherwise pointing the app at an existing KeePassXC
        // vault would duplicate every entry in it.
        val adoptableByContentKey = local.values.filter { kind.syncId(it) !in remote }
            .associateBy({ kind.contentKey(it) }, { kind.syncId(it) })
        val claimed = mutableSetOf<String>()
        for (entry in unidentified) {
            val match = adoptableByContentKey[kind.contentKey(kind.entity(entry))]
            val id = match?.takeIf { it !in claimed && it !in remote } ?: newSyncId()
            claimed += id
            remote[id] = entry + (EntryMapper.FIELD_SYNC_ID to id)
            remoteChanged = true
        }

        for (id in local.keys + remote.keys) {
            val localEntity = local[id]
            val remoteEntry = remote[id]
            val base = baseline[id]

            if (localEntity != null && remoteEntry != null) {
                val localFields = kind.fields(localEntity)
                val remoteEntity = kind.entity(remoteEntry)
                val remoteFields = kind.fields(remoteEntity)
                val localHash = EntryMapper.contentHash(localFields)
                val remoteHash = EntryMapper.contentHash(remoteFields)

                if (localHash == remoteHash) {
                    remoteOut += remoteEntry
                    if (id in freshlyIdentified) localUpserts += localEntity
                    snapshots += kind.snapshot(id, localHash, kind.updatedAt(localEntity), kind.updatedAt(remoteEntity), now)
                    continue
                }

                val localDirty = base == null || localHash != base.contentHash
                val remoteDirty = base == null || remoteHash != base.contentHash
                val localWins = when {
                    localDirty && remoteDirty -> kind.updatedAt(localEntity) >= kind.updatedAt(remoteEntity)
                    else -> localDirty
                }

                if (localWins) {
                    // Keep fields no client of ours understands (Notes, Tags, ...).
                    remoteOut += remoteEntry.filterKeys { it !in EntryMapper.OWNED_KEYS } + localFields
                    remoteChanged = true
                    pushed++
                    if (id in freshlyIdentified) localUpserts += localEntity
                    val stamp = kind.updatedAt(localEntity)
                    snapshots += kind.snapshot(id, localHash, stamp, stamp, now)
                } else {
                    // Pulled rows keep the remote timestamp, so the next cycle sees them
                    // as clean rather than bouncing the change back.
                    localUpserts += kind.rebind(remoteEntity, kind.rowId(localEntity), id)
                    remoteOut += remoteEntry
                    pulled++
                    val stamp = kind.updatedAt(remoteEntity)
                    snapshots += kind.snapshot(id, remoteHash, stamp, stamp, now)
                }
                continue
            }

            if (localEntity != null) {
                if (base != null) {
                    localDeletes += localEntity
                    removed += id
                    deletedLocal++
                } else {
                    val localFields = kind.fields(localEntity)
                    remoteOut += localFields
                    remoteChanged = true
                    pushed++
                    if (id in freshlyIdentified) localUpserts += localEntity
                    val stamp = kind.updatedAt(localEntity)
                    snapshots += kind.snapshot(id, EntryMapper.contentHash(localFields), stamp, stamp, now)
                }
                continue
            }

            val newRemote = remoteEntry!!
            if (base != null) {
                removed += id
                deletedRemote++
                remoteChanged = true
            } else {
                val remoteEntity = kind.entity(newRemote)
                localUpserts += kind.rebind(remoteEntity, 0, id)
                remoteOut += newRemote
                pulled++
                val stamp = kind.updatedAt(remoteEntity)
                snapshots += kind.snapshot(id, EntryMapper.contentHash(kind.fields(remoteEntity)), stamp, stamp, now)
            }
        }

        return Plan(
            localUpserts = localUpserts,
            localDeletes = localDeletes,
            remoteFields = remoteOut,
            snapshots = snapshots,
            removedSyncIds = removed,
            remoteChanged = remoteChanged,
            pushed = pushed,
            pulled = pulled,
            deletedLocal = deletedLocal,
            deletedRemote = deletedRemote,
        )
    }
}

private abstract class EntityKind<T> {
    abstract val kind: String
    abstract fun syncId(e: T): String
    abstract fun updatedAt(e: T): Long
    abstract fun rowId(e: T): Long
    abstract fun fields(e: T): Map<String, String>
    abstract fun entity(fields: Map<String, String>): T
    abstract fun contentKey(e: T): String
    abstract fun rebind(e: T, rowId: Long, syncId: String): T
    abstract suspend fun persist(e: T)
    abstract suspend fun delete(e: T)

    fun snapshot(syncId: String, hash: String, localUpdatedAt: Long, remoteModified: Long, now: Long) =
        SyncSnapshot(syncId, kind, hash, localUpdatedAt, remoteModified, now)
}

private class Plan<T>(
    val localUpserts: List<T>,
    val localDeletes: List<T>,
    val remoteFields: List<Map<String, String>>,
    val snapshots: List<SyncSnapshot>,
    val removedSyncIds: List<String>,
    val remoteChanged: Boolean,
    val pushed: Int,
    val pulled: Int,
    val deletedLocal: Int,
    val deletedRemote: Int,
)

object NativeKdbxCodec : KdbxCodec {
    override fun read(password: String, bytes: ByteArray): List<Map<String, String>>? {
        val json = KdbxNative.nativeImport(password, bytes) ?: return null
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            buildMap { for (key in entry.keys()) put(key, entry.getString(key)) }
        }
    }

    override fun write(password: String, entries: List<Map<String, String>>): ByteArray? {
        val array = JSONArray()
        for (entry in entries) array.put(JSONObject(entry as Map<*, *>))
        return KdbxNative.nativeExport(password, array.toString())
    }
}

class SafKdbxDocument(private val context: Context, private val uri: Uri) : KdbxDocument {
    override fun read(): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException("Cannot open $uri")

    override fun write(bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw FileNotFoundException("Cannot write $uri")
    }
}

/** Runs a sync from the persisted settings, recording the outcome for the settings screen. */
suspend fun runKdbxSync(context: Context, db: PasswordDatabase): KdbxSyncResult {
    if (!KdbxSyncSettings.enabled(context)) return KdbxSyncResult.NotConfigured
    val uri = KdbxSyncSettings.documentUri(context) ?: return KdbxSyncResult.NotConfigured
    val passwordHelper = KdbxPasswordHelper(context)
    if (!passwordHelper.isKeyGenerated()) return KdbxSyncResult.NotConfigured

    val result = try {
        KdbxSyncEngine(db, NativeKdbxCodec).sync(
            document = SafKdbxDocument(context, uri.toUri()),
            vaultPassword = passwordHelper.getPassphrase(),
            onBackup = { previous -> File(context.filesDir, BACKUP_FILE_NAME).writeBytes(previous) },
        )
    } catch (e: Exception) {
        KdbxSyncResult.Error(e.message ?: e.javaClass.simpleName)
    }

    when (result) {
        is KdbxSyncResult.Success -> KdbxSyncSettings.recordSuccess(context)
        KdbxSyncResult.NotConfigured -> Unit
        else -> KdbxSyncSettings.recordFailure(context, result.errorCode())
    }
    return result
}

/** Repository-based overload — preferred; avoids direct PasswordDatabase access. */
suspend fun runKdbxSync(context: Context, repository: PasswordRepository): KdbxSyncResult {
    if (!KdbxSyncSettings.enabled(context)) return KdbxSyncResult.NotConfigured
    val uri = KdbxSyncSettings.documentUri(context) ?: return KdbxSyncResult.NotConfigured
    val passwordHelper = KdbxPasswordHelper(context)
    if (!passwordHelper.isKeyGenerated()) return KdbxSyncResult.NotConfigured

    val result = try {
        KdbxSyncEngine(repository, NativeKdbxCodec).sync(
            document = SafKdbxDocument(context, uri.toUri()),
            vaultPassword = passwordHelper.getPassphrase(),
            onBackup = { previous -> File(context.filesDir, BACKUP_FILE_NAME).writeBytes(previous) },
        )
    } catch (e: Exception) {
        KdbxSyncResult.Error(e.message ?: e.javaClass.simpleName)
    }

    when (result) {
        is KdbxSyncResult.Success -> KdbxSyncSettings.recordSuccess(context)
        KdbxSyncResult.NotConfigured -> Unit
        else -> KdbxSyncSettings.recordFailure(context, result.errorCode())
    }
    return result
}

private const val BACKUP_FILE_NAME = "kdbx-sync-backup.kdbx"
