package com.vayunmathur.cast.tv.platform

import android.content.Context
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.library.util.DataStoreUtils

/**
 * The TV's long-term secrets: its own ML-KEM identity, and one device key per paired phone.
 *
 * Both are what make a second session pair silently, and both are excluded from backup and device
 * transfer (`res/xml/data_extraction_rules.xml`) - restoring them onto a different TV would let that
 * TV impersonate this one to an already-paired phone, with no code shown to give it away.
 *
 * `DataStoreUtils` is one store shared across the app, so keys are namespaced `tv_*`. The suspend
 * getters are used throughout rather than the synchronous ones: this is read on a cold service start,
 * where the eagerly-mirrored snapshot has not hydrated and the synchronous variants would answer
 * null for a phone that is in fact remembered.
 */
class PairingStore(context: Context) : E2eeKeyStore {

    private val store = DataStoreUtils.getInstance(context)

    // --- E2eeKeyStore, for PqcIdentity.loadOrCreate ---

    override suspend fun getBytes(name: String): ByteArray? = store.getByteArrayAwait("tv_$name")

    override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) {
        store.setByteArray("tv_$name", value, onlyIfAbsent)
    }

    // --- remembered phones ---

    /** The device key for [senderId], or null when this phone has never paired. */
    suspend fun deviceKey(senderId: String): ByteArray? =
        store.getByteArrayAwait(deviceKeyName(senderId))

    suspend fun remember(senderId: String, deviceKey: ByteArray) {
        store.setByteArray(deviceKeyName(senderId), deviceKey)
    }

    /**
     * This TV's own stable id, generated once.
     *
     * Not the ML-KEM bundle's hash: the id is published in the mDNS record, and a public identifier
     * derived from a key is one more thing to have to think about. A random id says nothing.
     */
    suspend fun deviceId(): String {
        store.getStringAwait(KEY_DEVICE_ID)?.let { return it }
        val id = java.util.UUID.randomUUID().toString().take(ID_LENGTH)
        store.setString(KEY_DEVICE_ID, id)
        // Re-read, so two services starting at once agree on which id won.
        return store.getStringAwait(KEY_DEVICE_ID) ?: id
    }

    /**
     * The name shown on the phone's device list and on the idle screen.
     *
     * Defaults to the TV's own Bluetooth-style device name when there is one, because "Living Room
     * TV" is what the user already calls it.
     */
    suspend fun friendlyName(fallback: String): String =
        store.getStringAwait(KEY_FRIENDLY_NAME)?.takeIf { it.isNotBlank() } ?: fallback

    private fun deviceKeyName(senderId: String): String = "tv_paired_$senderId"

    private companion object {
        const val KEY_DEVICE_ID = "tv_device_id"
        const val KEY_FRIENDLY_NAME = "tv_friendly_name"

        /** Short enough to fit an mDNS TXT value comfortably, long enough not to collide. */
        const val ID_LENGTH = 12
    }
}
