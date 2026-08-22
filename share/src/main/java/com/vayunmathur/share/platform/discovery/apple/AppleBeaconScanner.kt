package com.vayunmathur.share.platform.discovery.apple

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "AppleBeacon"

/**
 * Apple's Bluetooth SIG company identifier, as it appears in a BLE
 * `Manufacturer Specific Data` AD structure.
 *
 * On the wire the two ID bytes are little-endian (`4C 00`); Android's
 * [ScanRecord.getManufacturerSpecificData] and [ScanFilter] both take the decoded integer, so
 * this is `0x004C` rather than `0x4C00`.
 */
const val APPLE_COMPANY_ID: Int = 0x004C

/** AirDrop's TLV type inside Apple's manufacturer data. Always carries an 18-byte value. */
const val APPLE_TLV_AIRDROP: Int = 0x05

/**
 * Apple's documented-by-reverse-engineering TLV types, for readable logs.
 *
 * Names follow the published surveys of Apple's continuity/proximity BLE protocols. Only
 * [APPLE_TLV_AIRDROP] is load-bearing here; the rest exist so a capture can be read at a
 * glance and so an unexpected type is obviously unexpected.
 */
private val APPLE_TLV_NAMES: Map<Int, String> = mapOf(
    0x02 to "iBeacon",
    0x05 to "AirDrop",
    0x06 to "HomeKit",
    0x07 to "ProximityPairing",
    0x08 to "HeySiri",
    0x09 to "AirPlayTarget",
    0x0A to "AirPlaySource",
    0x0B to "MagicSwitch",
    0x0C to "Handoff",
    0x0D to "TetheringTargetPresence",
    0x0E to "TetheringSourcePresence",
    0x0F to "NearbyAction",
    0x10 to "NearbyInfo",
    0x12 to "FindMy",
)

/** One type-length-value record from an Apple manufacturer-data blob. */
data class AppleTlv(val type: Int, val value: ByteArray) {
    val name: String get() = APPLE_TLV_NAMES[type] ?: "Unknown"

    override fun toString(): String = "$name(0x%02x)[${value.size}B]=${value.toHex()}".format(type)

    // Identity has to be by content: these are compared to decide whether a beacon changed,
    // and the default array equality would report every rescan as a new observation.
    override fun equals(other: Any?): Boolean =
        this === other || (other is AppleTlv && type == other.type && value.contentEquals(other.value))

    override fun hashCode(): Int = 31 * type + value.contentHashCode()
}

/**
 * AirDrop's `0x05` record, as emitted by a device with the share sheet open.
 *
 * Layout of the 18-byte value, per the published analyses of Apple's AirDrop BLE beacon:
 * eight zero bytes, a version byte, then four two-byte truncated `SHA-256` prefixes of the
 * sender's contact identifiers (Apple ID, phone number, and two email addresses), then a
 * zero terminator. A receiver in contacts-only mode matches those prefixes against its own
 * address book to decide whether to answer.
 *
 * The prefixes are only two bytes each, so they identify nothing on their own — they are a
 * bloom-filter-style hint, not an identity. They are decoded here purely so a capture can be
 * correlated across adverts.
 */
data class AirDropBeacon(
    val version: Int,
    val appleIdHash: ByteArray,
    val phoneHash: ByteArray,
    val emailHash: ByteArray,
    val email2Hash: ByteArray,
) {
    /** The four contact-hash prefixes that are actually populated (Apple zero-fills unused slots). */
    fun populatedHashes(): List<String> =
        listOf(
            "appleId" to appleIdHash,
            "phone" to phoneHash,
            "email" to emailHash,
            "email2" to email2Hash,
        ).filter { (_, h) -> h.any { it != 0.toByte() } }
            .map { (label, h) -> "$label=${h.toHex()}" }

    override fun equals(other: Any?): Boolean = this === other || (
        other is AirDropBeacon &&
            version == other.version &&
            appleIdHash.contentEquals(other.appleIdHash) &&
            phoneHash.contentEquals(other.phoneHash) &&
            emailHash.contentEquals(other.emailHash) &&
            email2Hash.contentEquals(other.email2Hash)
        )

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + appleIdHash.contentHashCode()
        result = 31 * result + phoneHash.contentHashCode()
        result = 31 * result + emailHash.contentHashCode()
        result = 31 * result + email2Hash.contentHashCode()
        return result
    }

    companion object {
        private const val VALUE_LENGTH = 18
        private const val VERSION_OFFSET = 8

        /** Decode a `0x05` TLV value, or null if it is not the expected 18-byte shape. */
        fun parse(value: ByteArray): AirDropBeacon? {
            if (value.size < VALUE_LENGTH) return null
            fun pair(at: Int) = value.copyOfRange(at, at + 2)
            return AirDropBeacon(
                version = value[VERSION_OFFSET].toInt() and 0xFF,
                appleIdHash = pair(VERSION_OFFSET + 1),
                phoneHash = pair(VERSION_OFFSET + 3),
                emailHash = pair(VERSION_OFFSET + 5),
                email2Hash = pair(VERSION_OFFSET + 7),
            )
        }
    }
}

/**
 * A single sighting of an Apple device's manufacturer-data advertisement.
 *
 * [address] is the BLE address as reported by the scanner. Apple devices rotate a resolvable
 * private address every ~15 minutes, so it is a session key and not a device identity — do
 * not persist it or treat two different addresses as two different phones.
 */
data class AppleBeaconSighting(
    val address: String,
    val rssi: Int,
    val localName: String?,
    val tlvs: List<AppleTlv>,
    val raw: ByteArray,
    val seenAtMs: Long,
) {
    /** The decoded AirDrop record, when this advert carried one. */
    val airDrop: AirDropBeacon?
        get() = tlvs.firstOrNull { it.type == APPLE_TLV_AIRDROP }?.let { AirDropBeacon.parse(it.value) }

    /** True when this device currently has an AirDrop share sheet open. */
    val isAirDropping: Boolean get() = airDrop != null

    override fun equals(other: Any?): Boolean = this === other || (
        other is AppleBeaconSighting &&
            address == other.address &&
            tlvs == other.tlvs
        )

    override fun hashCode(): Int = 31 * address.hashCode() + tlvs.hashCode()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Decode Apple's manufacturer-specific data into its TLV records.
 *
 * The blob is a bare sequence of `type, length, value` triples with no header. A record whose
 * declared length runs past the end of the buffer terminates the parse rather than throwing:
 * BLE adverts get truncated by the controller, and a partial trailing record is normal.
 */
fun parseAppleTlvs(data: ByteArray): List<AppleTlv> {
    val out = mutableListOf<AppleTlv>()
    var i = 0
    while (i + 1 < data.size) {
        val type = data[i].toInt() and 0xFF
        val length = data[i + 1].toInt() and 0xFF
        val valueStart = i + 2
        val valueEnd = valueStart + length
        if (valueEnd > data.size) break
        out.add(AppleTlv(type, data.copyOfRange(valueStart, valueEnd)))
        i = valueEnd
    }
    return out
}

/**
 * Passive BLE observer for Apple continuity beacons, and specifically for AirDrop's `0x05`
 * record.
 *
 * ## What this can and cannot establish
 *
 * This is **reconnaissance, not a transport**. Seeing an AirDrop beacon proves a nearby Apple
 * device has its share sheet open and is soliciting receivers; it does not make this device
 * answerable. AirDrop's beacon exists only to wake nearby receivers' radios — the actual
 * discovery (`_airdrop._tcp`) and transfer (`HTTPS` `/Discover`, `/Ask`, `/Upload`) happen on
 * a peer-to-peer Wi-Fi link, not over BLE, and nothing in the beacon carries an address that
 * can be dialled.
 *
 * It exists so that the peer-to-peer link layer AirDrop actually uses can be identified
 * empirically, by correlating "an iPhone is beaconing AirDrop right now" against what
 * [WifiDirectProbe] and the mDNS browse see at the same instant. That correlation is the only
 * way to settle the question from an Android device; see `AIRDROP_FINDINGS.md`.
 *
 * Needs [Manifest.permission.BLUETOOTH_SCAN]. Declared `neverForLocation`, and nothing here
 * derives position.
 */
class AppleBeaconScanner(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var scanCallback: ScanCallback? = null

    private val _sightings = MutableStateFlow<Map<String, AppleBeaconSighting>>(emptyMap())

    /** Latest sighting per BLE address. Keyed on a rotating address, so entries are short-lived. */
    val sightings: StateFlow<Map<String, AppleBeaconSighting>> = _sightings.asStateFlow()

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Observe every Apple manufacturer-data advertisement in range.
     *
     * Filters on the company ID only, not on the AirDrop type byte. Apple packs several TLVs
     * into one advert in no guaranteed order, so a data-prefix filter anchored at offset 0
     * would silently miss any advert where AirDrop is not the first record. Filtering broadly
     * and decoding in software costs a few extra callbacks and cannot drop a match.
     */
    fun scan(): Flow<AppleBeaconSighting> = callbackFlow {
        // Point-in-time measurement: a beacon seen during a previous run is not evidence about
        // what is in range now.
        _sightings.value = emptyMap()
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.w(TAG, "scan denied: missing BLUETOOTH_SCAN")
            close()
            return@callbackFlow
        }
        val btAdapter = adapter ?: run {
            Log.w(TAG, "no BluetoothAdapter")
            close()
            return@callbackFlow
        }
        if (!btAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth disabled")
            close()
            return@callbackFlow
        }
        val scanner = btAdapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "bluetoothLeScanner null")
            close()
            return@callbackFlow
        }

        val filters = listOf(
            ScanFilter.Builder().setManufacturerData(APPLE_COMPANY_ID, ByteArray(0)).build(),
        )
        // Same settings as the Nearby Connections scan: setLegacy(false) is what makes
        // extended advertisements visible at all, and Apple devices do use them.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val data = record.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return
                val address = result.device?.address ?: return
                val tlvs = parseAppleTlvs(data)
                if (tlvs.isEmpty()) return
                val sighting = AppleBeaconSighting(
                    address = address,
                    rssi = result.rssi,
                    localName = record.deviceName,
                    tlvs = tlvs,
                    raw = data,
                    seenAtMs = System.currentTimeMillis(),
                )
                // Log only when the TLV set changes. An iPhone re-advertises several times a
                // second and an unconditional log buries the one transition that matters.
                val previous = _sightings.value[address]
                _sightings.value = _sightings.value + (address to sighting)
                if (previous == null || previous.tlvs != tlvs) {
                    val airDrop = sighting.airDrop
                    if (airDrop != null) {
                        Log.i(
                            TAG,
                            "AIRDROP beacon from $address rssi=${result.rssi} " +
                                "v${airDrop.version} ${airDrop.populatedHashes()} raw=${data.toHex()}",
                        )
                    } else {
                        Log.d(
                            TAG,
                            "apple beacon from $address rssi=${result.rssi} " +
                                tlvs.joinToString(" "),
                        )
                    }
                }
                trySend(sighting)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Apple beacon scan failed: $errorCode")
                close()
            }
        }
        scanCallback = callback
        try {
            @SuppressLint("MissingPermission")
            fun doStart() = scanner.startScan(filters, settings, callback)
            doStart()
            Log.i(TAG, "scanning for Apple manufacturer data (0x%04x)".format(APPLE_COMPANY_ID))
        } catch (e: Exception) {
            Log.w(TAG, "startScan threw", e)
            close(e)
            return@callbackFlow
        }
        awaitClose {
            @SuppressLint("MissingPermission")
            fun stopIfPermitted() {
                if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                    try {
                        scanner.stopScan(callback)
                    } catch (_: Exception) {
                    }
                }
            }
            stopIfPermitted()
            scanCallback = null
            Log.d(TAG, "Apple beacon scan stopped")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = scanCallback ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(cb)
        } catch (_: Exception) {
        }
        scanCallback = null
    }

    /** Drop sightings not refreshed within [maxAgeMs]; addresses rotate, so the map must not grow. */
    fun expire(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        _sightings.value = _sightings.value.filterValues { it.seenAtMs >= cutoff }
    }
}
