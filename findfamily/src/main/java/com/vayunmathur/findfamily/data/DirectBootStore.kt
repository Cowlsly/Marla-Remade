package com.vayunmathur.findfamily.data

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The slice of state a location publish needs, mirrored into device-protected storage.
 *
 * Everything findfamily normally reads at startup — the Room database and the default
 * DataStore — lives in credential-encrypted storage, which the kernel cannot decrypt until
 * the user types their passcode. That is why nothing was reported between a reboot and the
 * first unlock. Room and the main DataStore deliberately stay where they are: the location
 * history, contact names and photos, waypoints and share-link keys are the genuinely private
 * data and belong behind the passcode. Only what is strictly required to encrypt and address
 * an outbound fix is duplicated here.
 *
 * The mirror is written after unlock, from the credential-encrypted originals, and is only
 * ever read before one. [userid] returning null means it has not been seeded yet, which is
 * the expected state on the first boot after this ships.
 *
 * Note the identity keys below are private. They have to be reachable pre-unlock or an
 * authenticated, encrypted publish cannot be produced at all — that is inherent to reporting
 * before unlock rather than a shortcut taken here.
 */
object DirectBootStore {

    /** Same key names the credential-encrypted store uses, so the values copy across as-is. */
    private const val KEY_USERID = "userid"
    private const val KEY_ROSTER = "directboot_roster"

    /**
     * The two user-facing switches, mirrored so the pre-unlock path honours them. Without
     * these, someone who had turned tracking or sharing off would silently get both back for
     * the whole window between a reboot and their passcode.
     */
    private const val KEY_TRACKING_ENABLED = "tracking_enabled"
    private const val KEY_SHARING_ENABLED = "global_sharing_enabled"

    /**
     * The PQC identity blobs, named exactly as [com.vayunmathur.e2ee.PqcIdentity] stores them
     * under the `ff_pqc` prefix. All four must be present for the identity to load; a partial
     * copy would make `loadOrCreate` mint a fresh one (see [isSeeded]).
     */
    private val PQC_KEYS = listOf(
        "ff_pqcKemPub", "ff_pqcKemPriv", "ff_pqcDsaPub", "ff_pqcDsaPriv",
    )

    /** A peer we may publish to, reduced to the two fields the publish path actually reads. */
    @Serializable
    data class Target(val id: Long, val bundle: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun store(context: Context): DataStoreUtils =
        DataStoreUtils.getInstance(context, deviceProtected = true)

    /**
     * True once the mirror holds a usable identity. The pre-unlock path must refuse to run
     * without this: minting a userid or PQC identity here would not match the one in
     * credential-encrypted storage, and peers would see the publishes as coming from a
     * stranger they have never added.
     */
    suspend fun isSeeded(context: Context): Boolean {
        val ds = store(context)
        if (ds.getLongAwait(KEY_USERID) == null) return false
        return PQC_KEYS.all { ds.getByteArrayAwait(it) != null }
    }

    suspend fun userid(context: Context): Long? = store(context).getLongAwait(KEY_USERID)

    suspend fun roster(context: Context): List<Target> {
        val raw = store(context).getStringAwait(KEY_ROSTER) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Target.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    suspend fun isTrackingEnabled(context: Context): Boolean =
        store(context).getBooleanAwait(KEY_TRACKING_ENABLED, true)

    suspend fun isGlobalSharingEnabled(context: Context): Boolean =
        store(context).getBooleanAwait(KEY_SHARING_ENABLED, true)

    /**
     * Copy the current identity, switches and publish roster out of credential-encrypted
     * storage. Called after unlock, when both stores are readable, so the next reboot has
     * something to work from. [targets] should already be filtered to who we actually publish
     * to, so the pre-unlock path needs to make no policy decisions of its own.
     *
     * The identity keys are overwritten rather than filled in only when missing. Credential-
     * encrypted storage is authoritative for who we are, and it can legitimately re-mint:
     * `Networking.init` generates a fresh `userid` whenever CE has none, and
     * `PqcIdentity.loadOrCreate` does the same for the `ff_pqc*` blobs. If the mirror were
     * allowed to keep the older copy the two would drift apart, and every pre-unlock publish
     * would go out signed by an identity the recipients have never seen — silently, with
     * nothing in the UI to show for it.
     */
    suspend fun seed(
        context: Context,
        targets: List<Target>,
        trackingEnabled: Boolean,
        globalSharingEnabled: Boolean,
    ) {
        DataStoreUtils.seedDeviceProtectedStorage(
            context,
            listOf(KEY_USERID) + PQC_KEYS,
            overwrite = true,
        )
        val de = store(context)
        de.setBoolean(KEY_TRACKING_ENABLED, trackingEnabled)
        de.setBoolean(KEY_SHARING_ENABLED, globalSharingEnabled)
        de.setString(KEY_ROSTER, json.encodeToString(ListSerializer(Target.serializer()), targets))
    }
}
