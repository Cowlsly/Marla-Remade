// PACKAGE STRUCTURE EXCEPTION (JNI): FQN frozen for native RegisterNatives/symbol mangling
package com.vayunmathur.share.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The recovered fields of a Nearby Sharing endpoint-info blob. */
@Serializable
data class EndpointInfoFields(
    /** The peer's display name, absent when it advertises in contact-only mode. */
    val deviceName: String? = null,
    /** `p000\eanu.java` ordinal, driving which icon to render. */
    val deviceType: Int = 0,
    val version: Int = 0,
    val vendorId: Int = 0,
)

/** The recovered fields of a Nearby Connections `WifiLanServiceInfo` instance name. */
@Serializable
data class WifiLanServiceInfoFields(
    val endpointId: String,
    val pcp: Int,
)

/**
 * JNI surface for the native Quick Share protocol crate (libshare_nearby.so).
 *
 * Kotlin owns transport (NSD/BLE discovery + TCP sockets and file I/O); Rust owns the
 * pure state machine: the Nearby Connections connection handshake, UKEY2 with the
 * `AES_256_CBC-HMAC_SHA256` record protocol, the paired-key exchange, `OfflineFrame`
 * and Sharing `Frame` encode/decode, and payload chunking. No networking touches Rust.
 *
 * Threading: all calls are synchronous and must be serialized per session handle.
 * Call from a single coroutine / background thread per session.
 *
 * Lifecycle (see share/PROTOCOL_CONTRACT.md for the full spec):
 * ```
 * handle = nativeInit(localName, localEndpointInfo, localEndpointId, isInitiator)
 * repeat { if ((bytes = socket.read()) != null) nativeFeedInbound(handle, bytes) }
 * while ((rec = nativeDrainReceived(handle)) != null) appendToFile(rec)
 * while ((out = nativeDrainOutbound(handle)) != null) socket.write(out)
 * state = nativeQueryState(handle); files = nativeQueryPendingFiles(handle)
 * nativeAccept(handle, accept, destDir)     // after the user taps Accept/Reject
 * nativeDestroy(handle)                     // always, e.g. in finally / onCleared
 * ```
 *
 * Discovery constants are derived in Rust from `SHA-256("NearbySharing")` so that a
 * wrong digest fails a unit test rather than silently breaking discovery:
 * `nativeMdnsServiceType()` yields `_FC9F5ED42C8A._tcp` and `nativeBleServiceIdHash()`
 * the 3-byte `FC9F5E` used inside the BLE advertisement.
 */
internal object ShareNative {
    /**
     * Create a session. [isInitiator] must be `true` for the side that dialled the TCP
     * socket and `false` for the side that accepted it — only the initiator sends
     * `CONNECTION_REQUEST` and only the initiator is the UKEY2 client.
     *
     * [localEndpointId] must be the id this device advertises, so the peer sees the same
     * identity in `CONNECTION_REQUEST` that it discovered over mDNS. Empty falls back to
     * a fresh random id.
     */
    external fun nativeInit(
        localName: String,
        localEndpointInfo: ByteArray,
        localEndpointId: String,
        isInitiator: Boolean,
    ): Long

    external fun nativeFeedInbound(handle: Long, bytes: ByteArray): Int

    external fun nativeDrainOutbound(handle: Long): ByteArray?

    external fun nativeQueryState(handle: Long): Int

    /** JSON utf8 `[{"name","sizeBytes","mimeType"}]`, or null for a bad handle. */
    external fun nativeQueryPendingFiles(handle: Long): ByteArray?

    external fun nativeAccept(handle: Long, accept: Boolean, destDir: String): Int

    /** Stage the files to announce. [json] uses the `nativeQueryPendingFiles` shape. */
    external fun nativeSetFilesToSend(handle: Long, json: ByteArray): Int

    /**
     * Announce the staged files. Safe to call before the paired-key exchange finishes:
     * the frame is held and emitted once the session is ready.
     */
    external fun nativeQueueIntroduction(handle: Long): Int

    /** Emit a `KEEP_ALIVE` so a long transfer does not look idle. */
    external fun nativeSendKeepAlive(handle: Long): Int

    external fun nativeOpenFile(handle: Long, fileName: String, fileSize: Long): Int

    external fun nativeWriteChunk(handle: Long, chunk: ByteArray): Int

    external fun nativeCloseFile(handle: Long): Int

    /**
     * One received FILE chunk in the `PROTOCOL_CONTRACT.md` §6 record layout, or null
     * when nothing is pending. Call in a loop after every [nativeFeedInbound]: each
     * call hands over one chunk and drops it, so a large file costs constant memory.
     */
    external fun nativeDrainReceived(handle: Long): ByteArray?

    /** Why the session failed, or null while it is healthy. */
    external fun nativeQueryFailureReason(handle: Long): String?

    /** Recent protocol events, one per line — which frames each side actually exchanged. */
    external fun nativeQueryTrace(handle: Long): String?

    external fun nativeDestroy(handle: Long)

    // ------------------------------------------------------------------
    // Discovery — Nearby Connections service id, mDNS type, BleAdvertisement.
    // Implemented in share/src/main/rust/src/ble_adv.rs.
    // ------------------------------------------------------------------

    /** The mDNS service type to register and browse: `_FC9F5ED42C8A._tcp`. */
    external fun nativeMdnsServiceType(): String?

    /** The 3-byte truncated `SHA-256("NearbySharing")` used as the BLE `serviceIdHash`. */
    external fun nativeBleServiceIdHash(): ByteArray?

    /**
     * Build a Nearby Connections `BleAdvertisement` as service-data for GATT `0xFEF3`.
     *
     * [fast] selects the 27-byte legacy budget over extended advertising's 512
     * (`p000\dscb.java:110-123`); a real endpoint info only fits fast mode for a very
     * short device name. [deviceToken] must be empty or exactly 2 bytes. Null if it
     * would not fit.
     */
    external fun nativeBuildBleAdvertisement(
        data: ByteArray,
        deviceToken: ByteArray,
        fast: Boolean,
    ): ByteArray?

    /**
     * Parse `0xFEF3` service-data and return the advertisement's `data` field.
     * Null when the bytes are not a supported `BleAdvertisement` or when the embedded
     * `serviceIdHash` is not `"NearbySharing"`'s — which filters foreign advertisers.
     */
    external fun nativeParseBleAdvertisement(serviceData: ByteArray): ByteArray?

    /** `FC128E` ‖ [metadata] service-data for the `0xFE2C` FastInitiation beacon. */
    external fun nativeFastInitiationServiceData(metadata: ByteArray): ByteArray?

    // ------------------------------------------------------------------
    // Endpoint info + WifiLanServiceInfo — the two structures that make a device
    // *listed* by Quick Share. Implemented in
    // share/src/main/rust/src/endpoint_info.rs.
    //
    // Rust returns raw bytes and Kotlin applies Base64, because GMS uses the platform
    // API with flag `URL_SAFE or NO_PADDING or NO_WRAP` (`p000\bloa.java:29`).
    // ------------------------------------------------------------------

    /**
     * Build the Nearby Sharing endpoint-info blob a peer needs to list us.
     *
     * [deviceType] is a `p000\eanu.java` ordinal ([DEVICE_TYPE_PHONE] and friends).
     * Null for a blank [deviceName]; names longer than 32 bytes are truncated on a UTF-8
     * boundary.
     */
    external fun nativeBuildEndpointInfo(deviceName: String, deviceType: Int): ByteArray?

    /**
     * Parse an endpoint-info blob into JSON utf8
     * `{"deviceName":…,"deviceType":…,"version":…,"vendorId":…}`, with `deviceName`
     * absent for a contact-only advertisement.
     *
     * Null whenever a real device would reject the blob, so this doubles as a filter.
     */
    external fun nativeParseEndpointInfo(blob: ByteArray): ByteArray?

    /**
     * Build the 8 raw `WifiLanServiceInfo` bytes for [endpointId], which Base64-encode
     * into the mDNS instance name GMS expects. Null unless [endpointId] is exactly 4
     * ASCII characters.
     */
    external fun nativeBuildWifiLanServiceInfo(endpointId: String): ByteArray?

    /**
     * Parse a `WifiLanServiceInfo` into JSON utf8 `{"endpointId":…,"pcp":…}`, or null
     * when it fails the checks GMS applies at `p000\dnux.java:86-118`.
     */
    external fun nativeParseWifiLanServiceInfo(raw: ByteArray): ByteArray?

    /**
     * Wrap [endpointInfo] in the Nearby Connections BLE envelope for
     * `BleAdvertisement.data`: `pcp/version ‖ serviceIdHash ‖ endpointId ‖ len ‖ blob`.
     *
     * The bare blob is not enough — a peer that finds no endpoint id drops us silently.
     * Null unless [endpointId] is 4 ASCII characters and [endpointInfo] fits one length byte.
     */
    external fun nativeBuildBleEndpointPayload(
        endpointId: String,
        endpointInfo: ByteArray,
    ): ByteArray?

    /**
     * The endpoint-info blob nested inside a `BleAdvertisement.data` field, or null when
     * [data] is not a `"NearbySharing"` endpoint payload.
     */
    external fun nativeParseBleEndpointInfo(data: ByteArray): ByteArray?

    /** The peer's 4-character endpoint id from a `BleAdvertisement.data` field. */
    external fun nativeParseBleEndpointId(data: ByteArray): String?

    /**
     * [nativeParseEndpointInfo] decoded, or null when the blob is one a real device
     * would reject.
     */
    fun parseEndpointInfo(blob: ByteArray): EndpointInfoFields? {
        val json = nativeParseEndpointInfo(blob) ?: return null
        return runCatching {
            jsonFormat.decodeFromString<EndpointInfoFields>(json.decodeToString())
        }.getOrNull()
    }

    /**
     * [nativeParseWifiLanServiceInfo] decoded, or null when the bytes are not a
     * `WifiLanServiceInfo` GMS would accept.
     */
    fun parseWifiLanServiceInfo(raw: ByteArray): WifiLanServiceInfoFields? {
        val json = nativeParseWifiLanServiceInfo(raw) ?: return null
        return runCatching {
            jsonFormat.decodeFromString<WifiLanServiceInfoFields>(json.decodeToString())
        }.getOrNull()
    }

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // Nearby Presence — retained but NOT on the Quick Share path.
    //
    // Presence is a different subsystem advertising under 0xFCF1 with np_adv framing.
    // Quick Share advertises a Nearby Connections BleAdvertisement under 0xFEF3, so
    // discovery must use the codec above. BetoCore's credential/D2D/payload FFI has no
    // Java callers in GMS 26.24.34 and it could not be determined whether betocore is
    // live for Quick Share at all — see share/QUICK_SHARE_VERIFICATION.md.
    // ------------------------------------------------------------------

    external fun nativeBuildPresenceAdvert(deviceName: String): ByteArray?

    external fun nativeParsePresenceAdvert(serviceData: ByteArray): ByteArray?

    external fun nativeParsePresenceAdvertName(advertBytes: ByteArray): String?

    init {
        System.loadLibrary("share_nearby")
    }

    /** `p000\eanu.java:8` — the device type `:share` advertises. */
    const val DEVICE_TYPE_PHONE: Int = 1
}
