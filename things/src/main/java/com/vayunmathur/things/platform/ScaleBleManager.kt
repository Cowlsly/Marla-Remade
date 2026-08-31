package com.vayunmathur.things.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import com.vayunmathur.things.MainActivity
import java.util.UUID

/**
 * Offline BLE manager for the Renpho Elis 1 / Qingniu (Yolanda) scale.
 *
 * Protocol basis: renpho_analysis/OFFLINE_FEASIBILITY.md + YOLANDA_CALC_FORMULAS.md +
 * jadx-out/sources/com/qingniu and com/qn.
 *
 * - GATT services 0000FFE0 / 0000FFF0; notify chars 0000FFE1/0000FFF1/0000FFE2;
 *   write chars 0000FFE3/0000FFF2/0000FFE4 (QNBleConst, BleConst). We target the
 *   primary pair FFE0/FFE1/FFE3 which covers Elis 1.
 * - Scan-by-name: devices advertise as QN-Scale / QN-Scale1 / RENPHO / Elis (confirmed
 *   from BleConst.DEFAULT_BLE_SCALE_NAME="QN-Scale", DEFAULT_BLE_SCALE_NAME_1="QN-Scale1"
 *   and OFFLINE_FEASIBILITY packet captures). We filter on name prefix.
 * - Packet decode: QNDecoderImpl.decodeData — c=16 weight at bytes 3..4 (weightRatio 10/100),
 *   impedance via fourResTwoByte2Int(bytes 6..9) and eightResTwoByte2Double(kRatio=0.1);
 *   c=18 scale-info (weightRatio, units), c=35 stored history. We implement the minimal
 *   subset needed for offline live weighing.
 *
 * No wifi/internet/cloud/login — pure on-device BLE + BodyComposition math.
 */
@SuppressLint("MissingPermission")
class ScaleBleManager(private val activity: MainActivity) {

    companion object {
        // Primary Qingniu GATT (covers Elis 1)
        val SERVICE_FFE0: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val CHAR_FFE1: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        val CHAR_FFE3: UUID = UUID.fromString("0000FFE3-0000-1000-8000-00805F9B34FB")
        // Secondary (FFF0 family) — some firmware uses FFF1/FFF2 instead
        val SERVICE_FFF0: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        val CHAR_FFF1: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
        val CHAR_FFF2: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
        // Battery + Device Info
        val SERVICE_BATTERY: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val CHAR_BATTERY: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /** Name prefixes seen for Qingniu/Renpho scales. */
        val SCALE_NAME_PREFIXES = listOf("QN-Scale", "QN-S3", "RENPHO", "Elis", "Yolanda", "QIANGNIU")
    }

    data class ScaleBleDevice(val name: String, val address: String)

    private val bluetoothManager = activity.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager.adapter
    private val scanner get() = adapter.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    private var weightRatio = 10.0
    private var notifyChar: UUID = CHAR_FFE1
    private var writeChar: UUID = CHAR_FFE3
    private var serviceUuid: UUID = SERVICE_FFE0

    // For 8-electrode burst reassembly (count/cur at b[6]).
    private var kRatio = 0.1
    private var lf20k = 0.0; private var lf100k = 0.0
    private var rf20k = 0.0; private var rf100k = 0.0
    private var lh20k = 0.0; private var lh100k = 0.0
    private var rh20k = 0.0; private var rh100k = 0.0
    private var t20k = 0.0; private var t100k = 0.0

    private fun isScaleName(name: String?): Boolean {
        if (name == null) return false
        return SCALE_NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name
            if (!isScaleName(name)) return
            val addr = result.device.address
            if (activity.scaleDevices.none { it.address == addr }) {
                activity.scaleDevices.add(ScaleBleDevice(name ?: "Scale", addr))
            }
        }
    }

    fun startScan() {
        activity.scaleDevices.clear()
        activity.scaleScanning.value = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
        activity.scaleConnectionState.value = "Scanning scales..."
    }

    fun stopScan() {
        scanner?.stopScan(scanCallback)
        activity.scaleScanning.value = false
    }

    @Suppress("DEPRECATION")
    fun connect(address: String) {
        scanner?.stopScan(scanCallback)
        activity.scaleScanning.value = false
        activity.scaleConnectionState.value = "Connecting scale..."
        val device = adapter.getRemoteDevice(address)
        gatt = device.connectGatt(activity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    fun close() {
        gatt?.let {
            it.close()
            refreshCache(it)
        }
        gatt = null
    }

    private fun resetPacketState() {
        lf20k = 0.0; lf100k = 0.0; rf20k = 0.0; rf100k = 0.0
        lh20k = 0.0; lh100k = 0.0; rh20k = 0.0; rh100k = 0.0; t20k = 0.0; t100k = 0.0
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            activity.runOnUiThread {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        resetPacketState()
                        activity.scaleConnectionState.value = "Discovering services..."
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        activity.scaleConnectionState.value = "Disconnected"
                        activity.scaleDevices.clear()
                        resetPacketState()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            // Prefer FFE0/FFE1, fall back to FFF0/FFF1 if that's what the firmware exposes.
            val svc = g.getService(SERVICE_FFE0) ?: g.getService(SERVICE_FFF0)
            if (svc == null) {
                activity.runOnUiThread { activity.scaleConnectionState.value = "Scale service not found" }
                return
            }
            serviceUuid = svc.uuid
            val ch = svc.getCharacteristic(CHAR_FFE1) ?: svc.getCharacteristic(CHAR_FFF1)
            if (ch == null) {
                activity.runOnUiThread { activity.scaleConnectionState.value = "Scale notifying char not found" }
                return
            }
            notifyChar = ch.uuid
            writeChar = if (serviceUuid == SERVICE_FFF0) CHAR_FFF2 else CHAR_FFE3
            g.setCharacteristicNotification(ch, true)
            ch.getDescriptor(CCCD_UUID)?.let { desc ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            activity.runOnUiThread { activity.scaleConnectionState.value = "Connected — step on scale" }
            // Scale sends c=18 scale-info unsolicited; no write needed to start. We just listen.
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != notifyChar) return
            if (value.isEmpty()) return
            activity.runOnUiThread { dispatch(value) }
        }
    }

    private fun dispatch(value: ByteArray) {
        val cmd = value[0].toInt() and 0xFF
        when (cmd) {
            18 -> handleScaleInfo(value)
            16 -> handleMeasure(value)
            35 -> {
                // Stored replay — surface as weight-only for now; full replay via onGetStoredScale.
                // We treat stored the same as live: decode weight, notify.
                if (value.size >= 11) {
                    val w = decodeWeight(twoByteInt(value[9], value[10]), weightRatio)
                    if (w > 0) activity.onScaleMeasurement(w, 0, 0, false)
                }
            }
        }
    }

    private fun handleScaleInfo(v: ByteArray) {
        if (v.size < 11) return
        weightRatio = if ((v[10].toInt() and 0x01) == 1) 100.0 else 10.0
        // Units etc. available at v[10] bits, v[16] lbPrecision, v[17] unit mask.
        // We keep ratio for weight decode; other bytes inform display only.
        activity.scaleConnectionState.value = "Connected — step on scale"
    }

    private fun handleMeasure(v: ByteArray) {
        if (v.size < 6) return
        val c2 = v[5].toInt() and 0xFF
        val weight = decodeWeight(twoByteInt(v[3], v[4]), weightRatio)

        when (c2) {
            0 -> {
                // Real-time streaming weight
                if (weight > 0) activity.onScaleRealtimeWeight(weight)
            }
            1 -> {
                // Stabilized weight + 4-electrode impedance at 6..9 (fourResTwoByte2Int)
                if (v.size < 10) return
                val r50 = fourResTwoByte2Int(v[6], v[7])
                val r500 = fourResTwoByte2Int(v[8], v[9])
                activity.onScaleMeasurement(weight, r50, r500, true)
            }
            2 -> {
                // Overweight / error — treat as no-impedance measurement
                activity.onScaleMeasurement(weight, 0, 0, true)
            }
            else -> {
                // 8-electrode burst: top nibble=count, low nibble=current (QNDecoderImpl:472)
                if (v.size < 17) return
                val count = (v[6].toInt() and 0xFF shr 4) and 0x0F
                val cur = (v[6].toInt() and 0xFF) and 0x0F
                // Also handle alternate layout where count/cur packed differently; guard by size.
                // We use the explicit byte-6 layout from feasibility: ffs count/cur at b[6].
                // Some firmwares pack at b[5]; we handle both by checking which yields 1..3.
                val (cnt, curIdx) = if (count in 1..4 || cur in 0..4) count to cur else {
                    val b5 = v[5].toInt() and 0xFF
                    ((b5 shr 4) and 0x0F) to (b5 and 0x0F)
                }
                if (curIdx != cnt) {
                    // First packet of burst
                    lf20k = eightDouble(v[7], v[8])
                    lf100k = eightDouble(v[9], v[10])
                    rf20k = eightDouble(v[11], v[12])
                    rf100k = eightDouble(v[13], v[14])
                    lh20k = eightDouble(v[15], v[16])
                } else {
                    // Final packet
                    if (v.size >= 17) {
                        lh100k = eightDouble(v[7], v[8])
                        rh20k = eightDouble(v[9], v[10])
                        rh100k = eightDouble(v[11], v[12])
                        t20k = eightDouble(v[13], v[14])
                        t100k = eightDouble(v[15], v[16])
                        val seg = SegmentalImpedance(
                            rh20 = rh20k, lh20 = lh20k, t20 = t20k, rf20 = rf20k, lf20 = lf20k,
                            rh100 = rh100k, lh100 = lh100k, t100 = t100k, rf100 = rf100k, lf100 = lf100k,
                        )
                        val r50seg = (lh20k + rh20k).toInt()
                        val r500seg = (lh100k + rh100k).toInt()
                        activity.onScaleMeasurement(weight, r50seg, r500seg, true, seg)
                    }
                }
            }
        }
    }

    // Copy of MeasureDecoder helpers so we don't depend on the QN SDK.
    private fun twoByteInt(hi: Byte, lo: Byte): Int =
        ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)

    private fun fourResTwoByte2Int(b1: Byte, b2: Byte): Int {
        val v = twoByteInt(b1, b2)
        return if (v >= 60000) 0 else v
    }

    private fun eightDouble(b1: Byte, b2: Byte): Double {
        val v = twoByteInt(b1, b2)
        return v * kRatio
    }

    private fun decodeWeight(raw: Int, ratio: Double): Double {
        var w = raw.toDouble() / ratio
        while (w > 300.0) w /= 10.0
        return w
    }

    private fun refreshCache(g: BluetoothGatt) {
        runCatching { g.javaClass.getMethod("refresh").invoke(g) }
    }
}
