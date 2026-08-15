package com.vayunmathur.passwords.cable

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Broadcasts the caBLE v2 encrypted EID over BLE so the initiating browser can confirm proximity
 * and locate the tunnel. The 20-byte advert value ([CableEid.encrypt]) is placed in the service
 * data for the caBLE 16-bit UUID ([CableEid.SERVICE_DATA_UUID16]).
 *
 * New capability — no existing advertiser in the repo to copy; BLE client patterns follow
 * `things/util/BleManager.kt`.
 *
 * ⚠️ UNVERIFIED: whether the value belongs in service data (assumed) vs. manufacturer data, the
 * exact UUID, and advertising parameters. BLE advertising also varies by device/Android version.
 */
@SuppressLint("MissingPermission")
class CableAdvertiser(context: Context) {

    private val advertiser: BluetoothLeAdvertiser? =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser

    private var callback: AdvertiseCallback? = null

    /** True if the device can advertise (BT on, hardware supports LE advertising). */
    val isAvailable: Boolean get() = advertiser != null

    /**
     * Starts advertising [advertData] (the 20-byte encrypted EID). [onResult] reports success/failure.
     * Requires the BLUETOOTH_ADVERTISE runtime permission.
     */
    fun start(advertData: ByteArray, onResult: (Boolean) -> Unit) {
        val adv = advertiser ?: run { onResult(false); return }
        stop()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(uuid16(CableEid.SERVICE_DATA_UUID16)), advertData)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d(TAG, "caBLE advertising started")
                onResult(true)
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "caBLE advertising failed: $errorCode")
                onResult(false)
            }
        }
        callback = cb
        adv.startAdvertising(settings, data, cb)
    }

    fun stop() {
        val cb = callback ?: return
        runCatching { advertiser?.stopAdvertising(cb) }
        callback = null
    }

    companion object {
        private const val TAG = "CableAdvertiser"

        /** Expands a 16-bit Bluetooth UUID into its full 128-bit form. */
        fun uuid16(value: Int): UUID =
            UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(value and 0xFFFF))
    }
}
