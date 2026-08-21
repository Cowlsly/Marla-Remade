package com.vayunmathur.findfamily.tracker

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Binding side: discovers unprovisioned trackers over BLE and writes the
 * provisioning blob to them over GATT (see [TrackerBle] for the contract).
 *
 * The GATT round-trip (connect → discover services → write) is real; the only
 * device-dependent seam is the firmware's own persistence + mode switch after the
 * write, which can't be exercised without hardware. Errors are surfaced as `false`
 * / a closed flow rather than crashes.
 */
class TrackerProvisioner(private val context: Context) {

    /** Scans for trackers currently in pairing mode ([TrackerBle.UNPROVISIONED_SERVICE_UUID]). */
    @SuppressLint("MissingPermission")
    fun unprovisioned(): Flow<BluetoothDevice> = callbackFlow {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) { close(); return@callbackFlow }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "unprovisioned scan failed: $errorCode")
            }
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(TrackerBle.UNPROVISIONED_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: Exception) {
            close(e); return@callbackFlow
        }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /**
     * Connect to [device], discover services, and write the provisioning blob
     * `[8B trackerUserId BE][32B secret][8B unixSeconds BE]` to
     * [TrackerBle.PROVISION_CHARACTERISTIC_UUID]. Returns true iff the characteristic
     * write reports success.
     *
     * The trailing timestamp is the tracker's only source of wall-clock time: it has no
     * battery-backed RTC, and epoch ids are a function of `unix_seconds / 900`, so an
     * unsynced tracker would beacon ids outside the owner's
     * [TrackerProtocol.recentEpochIds] search window and resolve to nothing.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun provision(
        device: BluetoothDevice,
        trackerUserId: Long,
        secret: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean =
        suspendCancellableCoroutine { cont ->
            require(secret.size == TrackerProtocol.SECRET_LEN) { "secret must be ${TrackerProtocol.SECRET_LEN} bytes" }
            val blob = ByteArray(TrackerBle.PROVISION_BLOB_LEN)
            for (i in 0 until 8) blob[i] = (trackerUserId.toULong() shr (56 - i * 8)).toByte()
            secret.copyInto(blob, 8)
            val unixSeconds = (nowMs / 1000L).toULong()
            for (i in 0 until 8) blob[8 + secret.size + i] = (unixSeconds shr (56 - i * 8)).toByte()

            val resumed = AtomicBoolean(false)
            var gatt: BluetoothGatt? = null
            fun finish(result: Boolean) {
                if (resumed.compareAndSet(false, true)) {
                    runCatching { gatt?.disconnect() }
                    runCatching { gatt?.close() }
                    if (cont.isActive) cont.resume(result)
                }
            }

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        /*
                         * Ask for an MTU big enough to carry the whole blob in one ATT
                         * write. On the default 23-byte MTU only 20 bytes fit, so the
                         * stack would fall back to a long write — which works, but only
                         * if the peer reassembles chunked offsets, and it is slower.
                         * Service discovery waits for the MTU result.
                         */
                        if (!runCatching { g.requestMtu(PREFERRED_MTU) }.getOrDefault(false)) {
                            Log.w(TAG, "requestMtu failed; continuing on default MTU")
                            runCatching { g.discoverServices() }
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.i(TAG, "disconnected before the write completed (status=$status)")
                        finish(false)
                    }
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    Log.i(TAG, "MTU now $mtu (status=$status); discovering services")
                    runCatching { g.discoverServices() }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "service discovery failed: $status")
                        finish(false); return
                    }
                    val ch = g.getService(TrackerBle.UNPROVISIONED_SERVICE_UUID)
                        ?.getCharacteristic(TrackerBle.PROVISION_CHARACTERISTIC_UUID)
                    if (ch == null) {
                        Log.w(TAG, "provisioning characteristic not found on this device")
                        finish(false); return
                    }
                    val ok = writeChar(g, ch, blob)
                    if (!ok) finish(false)
                }

                @Deprecated("compat shim for API < 33")
                override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                    Log.i(TAG, "provisioning write completed with status=$status")
                    finish(status == BluetoothGatt.GATT_SUCCESS)
                }
            }

            gatt = try {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: Exception) {
                Log.w(TAG, "connectGatt failed", e); null
            }
            if (gatt == null) { finish(false); return@suspendCancellableCoroutine }

            cont.invokeOnCancellation { finish(false) }
        }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeChar(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                ch.value = value
                gatt.writeCharacteristic(ch)
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeCharacteristic failed", e); false
        }

    companion object {
        private const val TAG = "TrackerProvisioner"

        /**
         * Enough for `[TrackerBle.PROVISION_BLOB_LEN]` plus the 3-byte ATT write header,
         * with room to spare. Keeps the blob in a single write instead of a long write.
         */
        private const val PREFERRED_MTU = 64
    }
}
