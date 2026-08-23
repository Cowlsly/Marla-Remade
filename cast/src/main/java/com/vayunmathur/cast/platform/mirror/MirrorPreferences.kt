package com.vayunmathur.cast.platform.mirror

import com.vayunmathur.cast.domain.CastDevice
import android.content.Context
import com.vayunmathur.cast.protocol.VideoCodec
import com.vayunmathur.library.util.DataStoreUtils
import java.util.UUID

/**
 * What the phone remembers between sessions: the chosen TV, this phone's id, and one device key per
 * TV it has paired with.
 *
 * **Only the target is persisted, and deliberately not whether mirroring was on.** `:share` stores an
 * enabled flag and reconciles it after a reboot, but screen-capture consent is single-use and
 * unreplayable, so a flag saying "was mirroring" could never be acted on headlessly - it would only let
 * the tile lie about its state. Liveness therefore comes from the in-process session instead.
 *
 * A device key is what makes a second session pair silently, so losing one costs the user six digits
 * and nothing more. Keys are namespaced `cast_*` because `DataStoreUtils` is one store shared across
 * the app.
 */
object MirrorPreferences {

    private const val KEY_DEVICE_ID = "cast_target_id"
    private const val KEY_DEVICE_NAME = "cast_target_name"
    private const val KEY_DEVICE_HOST = "cast_target_host"
    private const val KEY_DEVICE_PORT = "cast_target_port"
    private const val KEY_SENDER_ID = "cast_sender_id"

    /**
     * The suspend getters, not the synchronous ones: the tile and the service both read this on a cold
     * start, where the eagerly-mirrored snapshot has not hydrated yet and the synchronous variants
     * would answer null for a target that is in fact stored.
     */
    suspend fun target(context: Context): CastDevice? {
        val store = DataStoreUtils.getInstance(context)
        val id = store.getStringAwait(KEY_DEVICE_ID) ?: return null
        val host = store.getStringAwait(KEY_DEVICE_HOST) ?: return null
        // The receiver binds an ephemeral control port, so unlike Cast's fixed 8009 there is nothing
        // to fall back to: without a stored port the target has to be rediscovered.
        val port = store.getLongAwait(KEY_DEVICE_PORT)?.toInt() ?: return null
        return CastDevice(
            id = id,
            friendlyName = store.getStringAwait(KEY_DEVICE_NAME) ?: host,
            host = host,
            port = port,
        )
    }

    suspend fun setTarget(context: Context, device: CastDevice) {
        val store = DataStoreUtils.getInstance(context)
        store.setString(KEY_DEVICE_ID, device.id)
        store.setString(KEY_DEVICE_NAME, device.friendlyName)
        store.setString(KEY_DEVICE_HOST, device.host)
        store.setLong(KEY_DEVICE_PORT, device.port.toLong())
    }

    /**
     * A stable id for this phone, generated once.
     *
     * Deliberately **not** a hardware identifier: it only tells a TV whether to put a pair code on
     * screen, so a random value is exactly as useful and reveals nothing about the device. Re-read
     * after writing so two callers racing on a cold start agree on which id won.
     */
    suspend fun senderId(context: Context): String {
        val store = DataStoreUtils.getInstance(context)
        store.getStringAwait(KEY_SENDER_ID)?.let { return it }
        val id = UUID.randomUUID().toString()
        store.setString(KEY_SENDER_ID, id)
        return store.getStringAwait(KEY_SENDER_ID) ?: id
    }

    /** The device key for [receiverId], or null when this TV has never been paired with. */
    suspend fun deviceKey(context: Context, receiverId: String): ByteArray? =
        DataStoreUtils.getInstance(context).getByteArrayAwait(deviceKeyName(receiverId))

    suspend fun rememberDeviceKey(context: Context, receiverId: String, deviceKey: ByteArray) {
        DataStoreUtils.getInstance(context).setByteArray(deviceKeyName(receiverId), deviceKey)
    }

    /**
     * The codecs that have already failed against [receiverId], so the next session skips them.
     *
     * **This is the recovery path, and it is deliberately not a mid-session renegotiation.** By the
     * time an AV1 failure is visible the codec is baked into `StreamConfig` and the encoder, the
     * `ScreenCapture` and the `VirtualDisplay` are all live; unpicking that safely is a great deal of
     * machinery for something that can be answered by remembering the answer instead. Recorded per TV
     * rather than globally because the failure may be either end's, and a phone that met one bad
     * decoder should not give up AV1 for every TV it ever sees.
     */
    suspend fun demotedCodecs(context: Context, receiverId: String): Set<VideoCodec> {
        val stored = DataStoreUtils.getInstance(context).getStringAwait(demotedName(receiverId))
            ?: return emptySet()
        return stored.split(',')
            .mapNotNull { name -> VideoCodec.entries.firstOrNull { it.name == name } }
            .toSet()
    }

    suspend fun demoteCodec(context: Context, receiverId: String, codec: VideoCodec) {
        val next = demotedCodecs(context, receiverId) + codec
        DataStoreUtils.getInstance(context)
            .setString(demotedName(receiverId), next.joinToString(",") { it.name })
    }

    /**
     * Forget what failed against [receiverId].
     *
     * Called on a fresh code pairing, which is the one point at which the TV might genuinely be a
     * different box - and the only reset a user can reach without a setting nobody would find. Without
     * it a single transient failure would pin a pairing to H.265 for good.
     */
    suspend fun clearDemotedCodecs(context: Context, receiverId: String) {
        DataStoreUtils.getInstance(context).setString(demotedName(receiverId), "")
    }

    private fun deviceKeyName(receiverId: String): String = "cast_paired_$receiverId"

    private fun demotedName(receiverId: String): String = "cast_demoted_codecs_$receiverId"
}
