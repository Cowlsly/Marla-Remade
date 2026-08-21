package com.vayunmathur.cast.platform.mirror

import android.content.Context
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.library.util.DataStoreUtils

/**
 * The chosen mirroring target, persisted so the Quick Settings tile can reconnect without waiting
 * for a full mDNS round trip.
 *
 * **Only the target is persisted, and deliberately not whether mirroring was on.** `:share` stores
 * an enabled flag and reconciles it after a reboot, but screen-capture consent is single-use and
 * unreplayable, so a flag saying "was mirroring" could never be acted on headlessly - it would only
 * let the tile lie about its state. Liveness therefore comes from the in-process session instead.
 *
 * Keys are namespaced `cast_*` because `DataStoreUtils` is one store shared across the app.
 */
object MirrorPreferences {

    private const val KEY_DEVICE_ID = "cast_target_id"
    private const val KEY_DEVICE_NAME = "cast_target_name"
    private const val KEY_DEVICE_HOST = "cast_target_host"
    private const val KEY_DEVICE_PORT = "cast_target_port"
    private const val KEY_DEVICE_CAPABILITIES = "cast_target_capabilities"

    /**
     * The suspend getters, not the synchronous ones: the tile and the service both read this on a
     * cold start, where the eagerly-mirrored snapshot has not hydrated yet and the synchronous
     * variants would answer null for a target that is in fact stored.
     */
    suspend fun target(context: Context): CastDevice? {
        val store = DataStoreUtils.getInstance(context)
        val id = store.getStringAwait(KEY_DEVICE_ID) ?: return null
        val host = store.getStringAwait(KEY_DEVICE_HOST) ?: return null
        val name = store.getStringAwait(KEY_DEVICE_NAME) ?: host
        return CastDevice(
            id = id,
            friendlyName = name,
            host = host,
            port = store.getLongAwait(KEY_DEVICE_PORT)?.toInt() ?: CAST_DEFAULT_PORT,
            capabilities = store.getLongAwait(KEY_DEVICE_CAPABILITIES)?.toInt() ?: 0,
        )
    }

    suspend fun setTarget(context: Context, device: CastDevice) {
        val store = DataStoreUtils.getInstance(context)
        store.setString(KEY_DEVICE_ID, device.id)
        store.setString(KEY_DEVICE_NAME, device.friendlyName)
        store.setString(KEY_DEVICE_HOST, device.host)
        store.setLong(KEY_DEVICE_PORT, device.port.toLong())
        // Kept because it is what decides audio-only, and the tile must know that before it can
        // pick an app id - without it a speaker would be offered video and refused at LAUNCH.
        store.setLong(KEY_DEVICE_CAPABILITIES, device.capabilities.toLong())
    }
}

private const val CAST_DEFAULT_PORT = 8009
