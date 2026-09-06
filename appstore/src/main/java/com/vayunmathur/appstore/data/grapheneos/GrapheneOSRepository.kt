package com.vayunmathur.appstore.data.grapheneos

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vayunmathur.appstore.data.security.Signify
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.GeneralSecurityException

private val Context.grapheneOSDataStore by preferencesDataStore(name = "grapheneos_repo")
private val INDEX_TIMESTAMP_KEY = longPreferencesKey("index_timestamp")

/**
 * The GrapheneOS Apps source: a signed index, and the packages this device can install from it.
 *
 * Trust comes entirely from one ed25519 signature over the index bytes ([Signify]) verified
 * against the pinned [GrapheneOSRepo.METADATA_PUBKEY], plus a monotonic timestamp that can
 * never go backwards. Every failure path leaves [packages] empty — fail closed — so a
 * tampered, unsigned or rolled-back index yields nothing installable rather than an install
 * with no signer or hash to check.
 *
 * That last part is the point of this class. The store used to build a download URL for the
 * Sandboxed Google Play packages by hand and carry no signer or hash at all, which the
 * verifier could only treat as "the source published nothing to check against" — while the
 * UI claimed the same guarantees as F-Droid. Those guarantees are now real, and an index
 * that fails to verify produces no download rather than an unchecked one.
 */
class GrapheneOSRepository(private val context: Context) {

    private val mutex = Mutex()

    @Volatile
    private var cached: List<GrapheneOSPackage> = emptyList()

    /** The last successfully verified listing, empty until [refresh] succeeds once. */
    val packages: List<GrapheneOSPackage> get() = cached

    fun packageFor(packageName: String): GrapheneOSPackage? =
        cached.firstOrNull { it.packageName == packageName }

    /**
     * Fetch, verify and parse the index for [wanted].
     *
     * A failure keeps whatever was verified last rather than emptying the list: the previous
     * answer was signed and is still true, and dropping it would take the section off the
     * screen every time the network is down.
     */
    suspend fun refresh(wanted: List<String>): Result<List<GrapheneOSPackage>> = mutex.withLock {
        try {
            val (status, _, bytes) =
                NetworkClient.performRequestBytesFull(GrapheneOSRepo.METADATA_URL)
            if (status !in 200..299) {
                return@withLock Result.failure(
                    IllegalStateException("index fetch failed: HTTP $status")
                )
            }

            val (json, signature) = split(bytes)
                ?: return@withLock Result.failure(
                    IllegalStateException("index is too short to carry a signature")
                )

            // Verify over the exact bytes downloaded, before parsing them.
            if (!Signify.verify(json, signature, GrapheneOSRepo.METADATA_PUBKEY)) {
                return@withLock Result.failure(
                    SecurityException("index signature did not verify")
                )
            }

            val index = JSONObject(json.toString(Charsets.UTF_8))

            // Anti-rollback: never accept an index older than the newest one already seen,
            // and never older than the pinned floor even on first run.
            val floor = maxOf(readStoredTimestamp(), GrapheneOSRepo.MIN_TIMESTAMP)
            val timestamp = GrapheneOSIndex.timestamp(index)
            if (timestamp < floor) {
                return@withLock Result.failure(
                    GeneralSecurityException("index timestamp $timestamp is older than $floor")
                )
            }
            persistTimestamp(timestamp)

            val parsed = GrapheneOSIndex.packagesFor(context, index, wanted)
            cached = parsed
            Result.success(parsed)
        } catch (e: Exception) {
            Log.w(TAG, "index fetch/verify failed", e)
            Result.failure(e)
        }
    }

    /**
     * Split the signed document into the bytes that were signed and the signature over them.
     *
     * The format is the JSON, a newline, 100 base64 characters and a final newline — see
     * GrapheneOS's `core/RepoRetriever.kt`. The signature is a fixed-width trailer rather
     * than a separate file, so it is sliced off by length.
     */
    private fun split(bytes: ByteArray): Pair<ByteArray, String>? {
        if (bytes.size <= TRAILER_BYTES) return null
        val json = bytes.copyOfRange(0, bytes.size - TRAILER_BYTES)
        val signature = bytes
            .copyOfRange(bytes.size - TRAILER_BYTES + 1, bytes.size - 1)
            .toString(Charsets.UTF_8)
        return json to signature
    }

    private suspend fun readStoredTimestamp(): Long = try {
        context.grapheneOSDataStore.data.first()[INDEX_TIMESTAMP_KEY] ?: 0L
    } catch (_: Exception) {
        0L
    }

    private suspend fun persistTimestamp(timestamp: Long) {
        try {
            context.grapheneOSDataStore.edit { it[INDEX_TIMESTAMP_KEY] = timestamp }
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "GrapheneOSRepository"

        /** Newline + 100 base64 signature characters + newline. */
        const val TRAILER_BYTES = 102
    }
}
