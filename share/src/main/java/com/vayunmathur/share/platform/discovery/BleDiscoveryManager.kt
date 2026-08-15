package com.vayunmathur.share.platform.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "BleDiscovery"

/** GATT service UUID 0xFCF1 (Nearby Presence). 128-bit expansion of 16-bit 0xFCF1. */
const val NEARBY_PRESENCE_SERVICE_UUID_STR = "0000fcf1-0000-1000-8000-00805f9b34fb"
val NEARBY_PRESENCE_SERVICE_UUID: ParcelUuid =
    ParcelUuid.fromString(NEARBY_PRESENCE_SERVICE_UUID_STR)

/**
 * BLE advertisement + scanning for Nearby Presence (BetoCore public-identity / Everyone mode).
 *
 * Modern stack (BetoCore):
 *  - Advertise: put the Nearby Presence advert BYTES built by the Rust np_adv JNI
 *    (UnencryptedEncoder, V0, DeviceInfo DE) as GATT service-data under UUID 0xFCF1.
 *    Until the Rust JNI is present, a Kotlin fallback builds the same minimal V0
 *    unencrypted advert so the module stays buildable and testable.
 *  - Scan: filter for 0xFCF1, extract service-data bytes, and pass them to the Rust
 *    parser (JSON byte[] per rustdev's contract) or the String alias / Kotlin fallback
 *    to derive the human-readable device name. Identity comes from the BLE presence
 *    advert only — no plaintext endpoint_info.
 *
 * Legacy FC128E01 / 0xFE2C (Google Nearby service-data beacon) has been removed per
 * the migration spec. No fallback advertises under the legacy UUID.
 */
class BleDiscoveryManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    private val _bleDevices = MutableStateFlow<Map<String, NearbyDevice>>(emptyMap())
    val bleDevices: StateFlow<Map<String, NearbyDevice>> = _bleDevices.asStateFlow()

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // Presence advert bytes — Rust JNI preferred, Kotlin fallback
    // ------------------------------------------------------------------

    private fun buildPresenceAdvertBytes(deviceName: String): ByteArray {
        try {
            val viaRust = tryBuildPresenceViaRust(deviceName)
            if (viaRust != null && viaRust.isNotEmpty()) return viaRust
        } catch (_: UnsatisfiedLinkError) {
        } catch (_: NoSuchMethodError) {
        } catch (e: Exception) {
            Log.d(TAG, "Rust presence build failed, using Kotlin fallback: ${e.message}")
        }
        return buildPresenceAdvertKotlinFallback(deviceName)
    }

    private fun tryBuildPresenceViaRust(deviceName: String): ByteArray? {
        return try {
            @Suppress("DEPRECATION")
            com.vayunmathur.share.protocol.ShareNative.nativeBuildPresenceAdvert(deviceName)
        } catch (_: UnsatisfiedLinkError) { null
        } catch (_: NoSuchMethodError) { null
        } catch (_: ClassNotFoundException) { null }
    }

    /**
     * Minimal V0 unencrypted Nearby Presence advert: [version=0x00][TxPower DE][DeviceInfo DE].
     * Mirrors np_adv's AdvBuilder<UnencryptedEncoder> path so scanners (including our Rust parser)
     * decode it. Device name is clamped to 5..9 bytes per DeviceInfo spec; shorter names are padded
     * with spaces, longer names are truncated with the truncated bit set.
     */
    internal fun buildPresenceAdvertKotlinFallback(deviceName: String): ByteArray {
        val versionHeader = 0x00.toByte()
        val txPowerDe = byteArrayOf(0x15.toByte(), 0x00.toByte()) // header len=1 type=5 => 0x15, payload 0
        val raw = deviceName.toByteArray(Charsets.UTF_8)
        val nameBytes: ByteArray
        val truncated: Boolean
        if (raw.size <= 9) {
            val padded = if (raw.size < 5) raw + ByteArray(5 - raw.size) { 0x20 } else raw
            nameBytes = padded
            truncated = false
        } else {
            nameBytes = raw.copyOf(9)
            truncated = true
        }
        val typeByte = (1 or (if (truncated) 0x80 else 0x00)).toByte()
        val deviceInfoContent = byteArrayOf(typeByte) + nameBytes
        val deLen = deviceInfoContent.size
        val header = ((deLen shl 4) or 0x03).toByte()
        val deviceInfoDe = byteArrayOf(header) + deviceInfoContent
        return byteArrayOf(versionHeader) + txPowerDe + deviceInfoDe
    }

    private fun parsePresenceName(advertBytes: ByteArray): String? {
        try {
            val viaRust = tryParsePresenceViaRust(advertBytes)
            if (!viaRust.isNullOrBlank()) return viaRust
        } catch (_: UnsatisfiedLinkError) {
        } catch (_: NoSuchMethodError) {
        } catch (e: Exception) {
            Log.d(TAG, "Rust presence parse failed, using Kotlin fallback: ${e.message}")
        }
        return parsePresenceNameKotlinFallback(advertBytes)
    }

    private fun tryParsePresenceViaRust(advertBytes: ByteArray): String? {
        return try {
            // Primary: JSON byte[] {"deviceName":"...","deviceType":1,...} — extract deviceName.
            val jsonBytes = try { com.vayunmathur.share.protocol.ShareNative.nativeParsePresenceAdvert(advertBytes) } catch (_: NoSuchMethodError) { null }
            if (jsonBytes != null && jsonBytes.isNotEmpty()) {
                val json = String(jsonBytes, Charsets.UTF_8)
                Regex(""""deviceName"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1)?.let { if (it.isNotBlank()) return it }
                // Fallback: some builds return raw name in the bytes
                val trimmed = json.trim().trim('"')
                if (trimmed.isNotBlank() && !trimmed.startsWith("{")) return trimmed
            }
            // Alias: String return for older builds
            @Suppress("DEPRECATION")
            com.vayunmathur.share.protocol.ShareNative.nativeParsePresenceAdvertName(advertBytes)
        } catch (_: UnsatisfiedLinkError) { null
        } catch (_: NoSuchMethodError) { null }
    }

    /**
     * Decode V0 advert bytes minimally: skip version header (1 byte), iterate DEs
     * [header][contents], and extract DeviceInfo's name (type 0x03). Returns null if not found.
     */
    internal fun parsePresenceNameKotlinFallback(advertBytes: ByteArray): String? {
        if (advertBytes.isEmpty()) return null
        val version = advertBytes[0].toInt() and 0xFF
        val isLdt = (version and 0x04) != 0
        if (isLdt) return null
        var off = 1
        while (off < advertBytes.size) {
            val hdr = advertBytes[off].toInt() and 0xFF
            val encLen = (hdr ushr 4) and 0x0F
            val typeCode = hdr and 0x0F
            val actualLen = encLen
            if (off + 1 + actualLen > advertBytes.size) break
            val contents = advertBytes.copyOfRange(off + 1, off + 1 + actualLen)
            if (typeCode == 0x03 && contents.size >= 2) {
                val nameBytes = contents.copyOfRange(1, contents.size)
                val raw = String(nameBytes, Charsets.UTF_8).trimEnd()
                if (raw.isNotBlank()) return raw
            }
            off += 1 + actualLen
        }
        return null
    }

    // ------------------------------------------------------------------
    // Advertising (Receive: "visible to nearby devices")
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startAdvertising(endpointName: String): Boolean {
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            Log.w(TAG, "startAdvertising denied: missing BLUETOOTH_ADVERTISE")
            return false
        }
        val btAdapter = adapter ?: run {
            Log.w(TAG, "no BluetoothAdapter — cannot advertise")
            return false
        }
        if (!btAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth disabled — cannot advertise")
            return false
        }
        val adv = btAdapter.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "bluetoothLeAdvertiser null — cannot advertise")
            return false
        }
        stopAdvertising()
        val presenceBytes = buildPresenceAdvertBytes(endpointName)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(NEARBY_PRESENCE_SERVICE_UUID)
            .addServiceData(NEARBY_PRESENCE_SERVICE_UUID, presenceBytes)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "BLE advertising started (0xFCF1, ${presenceBytes.size}B) as '$endpointName'")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "BLE advertising failed: $errorCode")
            }
        }
        advertiseCallback = callback
        advertiser = adv
        return try {
            adv.startAdvertising(settings, data, callback)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startAdvertising threw", e)
            advertiseCallback = null
            advertiser = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        val cb = advertiseCallback ?: return
        val adv = advertiser ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) return
        try {
            adv.stopAdvertising(cb)
        } catch (_: Exception) {
        }
        advertiseCallback = null
        advertiser = null
        Log.d(TAG, "BLE advertising stopped")
    }

    // ------------------------------------------------------------------
    // Scanning (Send: discover peers)
    // ------------------------------------------------------------------

    fun scan(): Flow<NearbyDevice> = callbackFlow {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.w(TAG, "scan denied: missing BLUETOOTH_SCAN")
            close()
            return@callbackFlow
        }
        val btAdapter = adapter ?: run {
            close()
            return@callbackFlow
        }
        if (!btAdapter.isEnabled) {
            close()
            return@callbackFlow
        }
        val scanner = btAdapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "bluetoothLeScanner null")
            close()
            return@callbackFlow
        }
        val filterFcf1 = ScanFilter.Builder().setServiceUuid(NEARBY_PRESENCE_SERVICE_UUID).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val data = record.getServiceData(NEARBY_PRESENCE_SERVICE_UUID) ?: return
                val addr = result.device.address ?: return
                val presenceName = parsePresenceName(data)
                val displayName = presenceName
                    ?: result.device.name
                    ?: record.deviceName
                    ?: addr
                val dev = NearbyDevice(
                    endpointId = addr,
                    endpointName = displayName,
                    host = null,
                    port = null,
                    source = DiscoverySource.Ble,
                    extra = data.joinToString(",") { "%02x".format(it) },
                )
                _bleDevices.value = _bleDevices.value.toMutableMap().apply { put(addr, dev) }
                trySend(dev)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: $errorCode")
                close()
            }
        }
        scanCallback = callback
        try {
            @SuppressLint("MissingPermission")
            fun doStart() = scanner.startScan(listOf(filterFcf1), settings, callback)
            doStart()
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
                    } catch (_: SecurityException) {
                    } catch (_: Exception) {
                    }
                }
            }
            stopIfPermitted()
            scanCallback = null
            Log.d(TAG, "BLE scan stopped")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = scanCallback ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(cb)
        } catch (_: Exception) {
        }
        scanCallback = null
    }

    fun release() {
        stopAdvertising()
        stopScan()
    }
}
