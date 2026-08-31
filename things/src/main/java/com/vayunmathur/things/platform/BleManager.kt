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
import java.util.Calendar
import java.util.UUID

/** One drained drink log read from the bottle's offline history (or live). */
data class HydrationReading(
    val amountMl: Int,
    val epochMillis: Long,
    val tds: Int?,
    val tempC: Int?,
)

/** Live sensor snapshot from the bottle. Fields are null until the bottle reports them. */
data class BottleStatus(
    val tempC: Int?,
    val tds: Int?,
    val batteryPct: Int?,
    val charging: Boolean,
)

/**
 * Speaks the WaterH bottle's BLE protocol. The bottle talks over two GATT services: it
 * notifies on FFE4 and accepts commands on FFE9. Because a GATT stack allows only one
 * outstanding write, commands are queued and drained on [onCharacteristicWrite].
 *
 * Everything stays on-device: we reimplement only the local BLE half of the official app
 * and never touch its cloud backend.
 */
@SuppressLint("MissingPermission")
class BleManager(private val activity: MainActivity) {
    companion object {
        val NOTIFY_SERVICE_UUID: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000FFE4-0000-1000-8000-00805F9B34FB")
        val WRITE_SERVICE_UUID: UUID = UUID.fromString("0000FFE5-0000-1000-8000-00805F9B34FB")
        val WRITE_CHAR_UUID: UUID = UUID.fromString("0000FFE9-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Command to request the bottle's water-log history.
        private const val CMD_REQUEST_LOGS = "4754000106"
        // Command to request the full bottle-data snapshot.
        private const val CMD_REQUEST_DATA = "47540001ff"
        // Set language (final step of the sync handshake).
        private const val CMD_SET_LANGUAGE = "50540003021b01"
    }

    data class BleDevice(val name: String, val address: String)

    private val bluetoothManager = activity.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager.adapter
    private val scanner get() = adapter.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    // Command queue: one outstanding write at a time, drained on onCharacteristicWrite.
    private val commandQueue = ArrayDeque<String>()
    private var writing = false

    // Live sensor state, merged across partial RT updates and emitted as a BottleStatus.
    private var curTemp: Int? = null
    private var curTds: Int? = null
    private var curBattery: Int? = null
    private var curCharging = false

    // Water-log accumulation across PT packets.
    private var expectedLogs = 0
    private val collected = ArrayList<HydrationReading>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (!name.startsWith("WaterH", ignoreCase = true)) return
            val addr = result.device.address
            if (activity.discoveredDevices.none { it.address == addr }) {
                activity.discoveredDevices.add(BleDevice(name, addr))
            }
        }
    }

    fun startScan() {
        activity.discoveredDevices.clear()
        activity.scanning.value = true
        // WaterH advertises no service UUID; discover by name instead of a ScanFilter.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
        activity.connectionState.value = "Scanning..."
    }

    @Suppress("DEPRECATION")
    fun connect(address: String) {
        scanner?.stopScan(scanCallback)
        activity.scanning.value = false
        activity.connectionState.value = "Connecting..."
        val device = adapter.getRemoteDevice(address)
        gatt = device.connectGatt(activity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    fun close() {
        gatt?.let {
            it.close()
            // Clear the GATT service cache so a later reconnect re-reads services fresh.
            refreshDeviceCache(it)
        }
        gatt = null
    }

    private fun resetState() {
        commandQueue.clear()
        writing = false
        curTemp = null
        curTds = null
        curBattery = null
        curCharging = false
        expectedLogs = 0
        collected.clear()
    }

    private fun enqueueCommand(hex: String) {
        activity.runOnUiThread {
            commandQueue.addLast(hex)
            if (!writing) writeNext()
        }
    }

    @Suppress("DEPRECATION")
    private fun writeNext() {
        val g = gatt ?: return
        val hex = commandQueue.removeFirstOrNull() ?: return
        val service = g.getService(WRITE_SERVICE_UUID) ?: return
        val char = service.getCharacteristic(WRITE_CHAR_UUID) ?: return
        val bytes = hexToByteArray(hex)
        writing = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            char.setValue(bytes)
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(char)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            activity.runOnUiThread {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        resetState()
                        activity.connectionState.value = "Connected"
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        activity.connectionState.value = "Disconnected"
                        activity.discoveredDevices.clear()
                        resetState()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = g.getService(NOTIFY_SERVICE_UUID) ?: return
            val char = service.getCharacteristic(NOTIFY_CHAR_UUID) ?: return
            g.setCharacteristicNotification(char, true)
            char.getDescriptor(CCCD_UUID)?.let {
                // The (descriptor, value) overload is API 33+; below that the
                // value has to be staged on the descriptor first.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    it.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(it)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Notifications are on; kick off the handshake by asking for full bottle data.
            enqueueCommand(CMD_REQUEST_DATA)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            activity.runOnUiThread {
                writing = false
                if (commandQueue.isNotEmpty()) writeNext()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (char.uuid != NOTIFY_CHAR_UUID || value.size < 2) return
            activity.runOnUiThread { dispatch(value) }
        }
    }

    private fun dispatch(value: ByteArray) {
        val b0 = value[0].toInt() and 0xFF
        val b1 = value[1].toInt() and 0xFF
        when {
            // RP snapshot / ack (bottle -> phone data reports).
            b0 == 0x52 && b1 == 0x50 -> handleRp(value)
            // RT incremental sensor update.
            b0 == 0x52 && b1 == 0x54 -> handleRt(value)
            // PT water-log stream (first packet).
            b0 == 0x50 && b1 == 0x54 -> handlePtFirst(value)
            // Water-log continuation packet.
            b1 == 0x06 -> handlePtContinuation(value)
        }
    }

    private fun handleRp(value: ByteArray) {
        if (value.size < 6) return
        val b2 = value[2].toInt() and 0xFF
        val b3 = value[3].toInt() and 0xFF
        val b5 = value[5].toInt() and 0xFF

        // Registration signature response (factory-fresh bottle): prompt for button press.
        if (b5 == 0x20) {
            activity.connectionState.value = "Press the bottle button"
            return
        }

        if (b2 == 0x00 && b3 == 0x31 && value.size > 50) {
            // Vita full snapshot.
            curTemp = value[6].toInt()
            curBattery = value[9].toInt() and 0xFF
            curCharging = value[34].toInt() and 0xFF in intArrayOf(1, 2)
            curTds = beInt(value, 45)
            emitStatus()
            enqueueSyncAndLanguage()
            return
        }
        if (b2 == 0x00 && b3 == 0x27 && value.size > 31) {
            // Boost full snapshot (temp/tds/volume arrive later via RT).
            curBattery = value[6].toInt() and 0xFF
            curCharging = value[31].toInt() and 0xFF in intArrayOf(1, 2)
            emitStatus()
            enqueueSyncAndLanguage()
            return
        }
        if (b2 == 0x00 && b3 == 0x0F) {
            // Sync setting acknowledged; now request the water logs.
            enqueueCommand(CMD_REQUEST_LOGS)
            return
        }
        // Water-log availability flag: value[6]==0 means no offline logs to drain.
        if (b5 == 0x06) return
        // Registration state on the paired path: prompt the user.
        if (b5 == 0x1C) {
            activity.connectionState.value = "Press the bottle button"
        }
    }

    private fun handleRt(value: ByteArray) {
        if (value.size < 7) return
        when (value[5].toInt() and 0xFF) {
            0x01 -> curTemp = value[6].toInt()
            0x02 -> curBattery = value[6].toInt() and 0xFF
            0x17 -> curCharging = value[6].toInt() and 0xFF in intArrayOf(1, 2)
            0x08 -> {
                // Volume changed => the user drank; re-request logs to pick it up live.
                enqueueCommand(CMD_REQUEST_LOGS)
                return
            }
            0x28 -> {
                if (value.size < 8) return
                curTds = beInt(value, 6)
            }
            0x1C -> {
                activity.connectionState.value = "Press the bottle button"
                return
            }
            else -> return
        }
        emitStatus()
    }

    private fun handlePtFirst(value: ByteArray) {
        if (value.size < 6 || (value[5].toInt() and 0xFF) != 0x06) return
        expectedLogs = beInt(value, 2) / RECORD_SIZE
        collected.clear()
        accumulate(value, 6)
    }

    private fun handlePtContinuation(value: ByteArray) {
        accumulate(value, 2)
    }

    private fun accumulate(value: ByteArray, start: Int) {
        var i = start
        while (i + RECORD_SIZE <= value.size) {
            collected.add(parseLogRecord(value, i))
            i += RECORD_SIZE
        }
        if (expectedLogs > 0 && collected.size >= expectedLogs) {
            collected.forEach { activity.onDrinkLog(it) }
            // Acknowledge/clear the drained logs from the bottle so each is counted once.
            enqueueCommand("525000040306" + toHex(expectedLogs * RECORD_SIZE, 4))
            expectedLogs = 0
            collected.clear()
        }
    }

    private fun enqueueSyncAndLanguage() {
        enqueueCommand(buildSyncCommand())
        enqueueCommand(CMD_SET_LANGUAGE)
    }

    private fun buildSyncCommand(): String {
        val cal = Calendar.getInstance()
        val yy = cal.get(Calendar.YEAR) % 100
        val mo = cal.get(Calendar.MONTH) + 1
        val dd = cal.get(Calendar.DAY_OF_MONTH)
        val hh = cal.get(Calendar.HOUR_OF_DAY)
        val mi = cal.get(Calendar.MINUTE)
        val ss = cal.get(Calendar.SECOND)
        // 505400140305 + goal(2B) + 0703 + YYMMDDHHmmss + 0726 + reminder(6B), defaults kept.
        return "505400140305" + "0000" + "0703" +
            toHex(yy, 2) + toHex(mo, 2) + toHex(dd, 2) +
            toHex(hh, 2) + toHex(mi, 2) + toHex(ss, 2) +
            "0726" + "00080014003C"
    }

    private fun emitStatus() {
        activity.onBottleStatus(BottleStatus(curTemp, curTds, curBattery, curCharging))
    }

    private fun parseLogRecord(b: ByteArray, off: Int): HydrationReading {
        val year = 2000 + (b[off].toInt() and 0xFF)
        val month = b[off + 1].toInt() and 0xFF
        val day = b[off + 2].toInt() and 0xFF
        val hour = b[off + 3].toInt() and 0xFF
        val min = b[off + 4].toInt() and 0xFF
        val sec = b[off + 5].toInt() and 0xFF
        val amount = beInt(b, off + 6)
        val tds = b[off + 8].toInt() and 0xFF
        val temp = b[off + 10].toInt()
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, day, hour, min, sec)
        return HydrationReading(amount, cal.timeInMillis, tds, temp)
    }

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun toHex(value: Int, padLen: Int): String {
        var h = Integer.toHexString(value)
        while (h.length < padLen) h = "0$h"
        return h
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val clean = hex.replace(Regex("\\s+"), "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun refreshDeviceCache(g: BluetoothGatt) {
        // The GATT cache refresh is a hidden API; WaterH uses it to avoid stale caches.
        runCatching {
            val refresh = g.javaClass.getMethod("refresh")
            refresh.invoke(g)
        }
    }
}

private const val RECORD_SIZE = 13
