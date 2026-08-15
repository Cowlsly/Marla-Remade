package com.vayunmathur.findfamily.tracker

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.UserKind
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Owner-side binding: mint a tracker identity, persist it, register it with the
 * server, and provision the physical device over BLE GATT. Centralised here (rather
 * than in the ViewModel) so the whole tracker feature stays in one package behind
 * `TrackerFeature.enabled`.
 */
object TrackerBinder {
    private const val TAG = "TrackerBinder"

    /**
     * Bind [device] as a new tracker named [name].
     *
     * Steps: generate an ML-KEM/ML-DSA keypair (public bundle stored on the `User`
     * row, private bundle kept owner-only in [TrackerStore]); generate a 32-byte
     * beacon secret; create the `User(kind = TRACKER)` row; register the tracker on
     * the server so finders can resolve it; write the provisioning blob to the
     * device. Returns true iff the GATT provisioning write succeeds.
     */
    suspend fun bind(context: Context, name: String, device: BluetoothDevice): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val repository = FindFamilyRepository.get(context)
                val store = TrackerStore(DataStoreUtils.getInstance(context))

                val keys = Networking.generatePqcKeyPair()
                val publicBundle = decodeB64(keys.publicBundleB64)
                val privateBundle = decodeB64(keys.privateBundleB64)
                val secret = ByteArray(TrackerProtocol.SECRET_LEN).also { SecureRandom().nextBytes(it) }
                val trackerId = Random.nextLong(from = 1, until = Long.MAX_VALUE)

                store.save(trackerId, secret, privateBundle)
                repository.upsertUser(
                    User(
                        name = name,
                        photo = null,
                        locationName = "Unknown Location",
                        sendingEnabled = false,
                        requestStatus = RequestStatus.MUTUAL_CONNECTION,
                        lastLocationChangeTime = Clock.System.now(),
                        encryptionKey = null,
                        id = trackerId,
                        pqcEncryptionKey = keys.publicBundleB64,
                        kind = UserKind.TRACKER,
                    )
                )
                // Best-effort: registration self-heals on the next heartbeat if the
                // socket is momentarily down (see pollTrackerReports).
                runCatching { Networking.registerTracker(trackerId, secret, publicBundle) }

                TrackerProvisioner(context).provision(device, trackerId, secret)
            }.onFailure { Log.w(TAG, "bind failed", it) }.getOrDefault(false)
        }

    private fun decodeB64(s: String): ByteArray =
        android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
}
