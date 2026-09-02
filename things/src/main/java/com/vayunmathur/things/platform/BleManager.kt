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
import android.util.Log
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
    val volumePct: Int?,
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
class BleManager {
    companion object {
        private const val TAG = "WaterHBle"
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
        // First-time setup: take the bottle out of factory mode, then start registration.
        // Mirrors the official app's registerDevice() path (requestExitFactoryMode +
        // requestRegistration); this is what lights the bottle's blue LED and pairs it.
        private const val CMD_EXIT_FACTORY_MODE = "5054000302f400"
        private const val CMD_REQUEST_REGISTRATION = "50540003021c01"
        // Sent after the user confirms on the bottle (registration "Confirmed"); the bottle then
        // finalizes and replies "registration successful / clear data successful".
        private const val CMD_CLEAR_OFFLINE_DATA = "50540003021c05"
    }

    data class BleDevice(val name: String, val address: String)

    private val bluetoothManager = DeviceController.appContext.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager.adapter
    private val scanner get() = adapter.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    // The device we want to stay connected to, and whether the last disconnect was user-initiated.
    // Used to keep connect() idempotent (so repeated auto-connect calls from the service don't
    // stack a second GATT client) and to drive passive background re-establishment.
    private var currentAddress: String? = null
    private var intentionalDisconnect = false
    // When true, run the one-time registration handshake on connect (blue LED + button press)
    // before requesting data, matching the official app's first-time setup path.
    private var pendingRegistration = false
    // Guards the one-shot clear-offline-data command sent after the button press is confirmed
    // (the bottle re-sends "Confirmed" several times).
    private var registrationClearSent = false

    // Command queue: one outstanding write at a time, drained on onCharacteristicWrite.
    private val commandQueue = ArrayDeque<String>()
    private var writing = false

    // Live sensor state, merged across partial RT updates and emitted as a BottleStatus.
    private var curTemp: Int? = null
    private var curTds: Int? = null
    private var curBattery: Int? = null
    private var curCharging = false
    private var curVolumePct: Int? = null

    // Water-log accumulation across PT packets.
    private var expectedLogs = 0
    private var parsedRecords = 0
    private val collected = ArrayList<HydrationReading>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (!name.startsWith("WaterH", ignoreCase = true)) return
            val addr = result.device.address
            if (DeviceController.discoveredDevices.none { it.address == addr }) {
                DeviceController.discoveredDevices.add(BleDevice(name, addr))
            }
        }
    }

    fun startScan() {
        DeviceController.discoveredDevices.clear()
        DeviceController.scanning.value = true
        // WaterH advertises no service UUID; discover by name instead of a ScanFilter.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
        DeviceController.connectionState.value = "Scanning..."
    }

    @Suppress("DEPRECATION")
    fun connect(address: String, register: Boolean = false) {
        // Idempotent: if we already hold a GATT client for this device, don't open a second one.
        // Each connectGatt registers a new client interface with the Bluetooth stack; stacking
        // them leaks interfaces and confuses the bottle, which only services its first session
        // (its command char then silently ignores writes → the app hangs on "Waiting for data").
        if (gatt != null && currentAddress == address) return
        // Release any stale/previous client before opening a fresh one.
        close()
        currentAddress = address
        intentionalDisconnect = false
        pendingRegistration = register
        registrationClearSent = false
        openGatt(address, autoConnect = false)
    }

    private fun openGatt(address: String, autoConnect: Boolean) {
        scanner?.stopScan(scanCallback)
        DeviceController.scanning.value = false
        DeviceController.connectionState.value = "Connecting..."
        val device = adapter.getRemoteDevice(address)
        gatt = device.connectGatt(DeviceController.appContext, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        intentionalDisconnect = true
        currentAddress = null
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
        curVolumePct = null
        expectedLogs = 0
        parsedRecords = 0
        collected.clear()
    }

    private fun enqueueCommand(hex: String) {
        DeviceController.runOnMain {
            commandQueue.addLast(hex)
            if (!writing) writeNext()
        }
    }

    @Suppress("DEPRECATION")
    private fun writeNext() {
        val g = gatt ?: return
        val hex = commandQueue.removeFirstOrNull() ?: return
        val service = g.getService(WRITE_SERVICE_UUID)
        if (service == null) {
            Log.w(TAG, "writeNext: WRITE service $WRITE_SERVICE_UUID not found; dropping $hex")
            writing = false
            return
        }
        val char = service.getCharacteristic(WRITE_CHAR_UUID)
        if (char == null) {
            Log.w(TAG, "writeNext: WRITE char $WRITE_CHAR_UUID not found; dropping $hex")
            writing = false
            return
        }
        val bytes = hexToByteArray(hex)
        writing = true
        // FFE9 on this bottle is write-without-response (properties=0x4). Match the write type to
        // the characteristic's actual properties: on Android 13+ a write-with-response issued to a
        // no-response-only characteristic is silently dropped at the ATT layer (it never leaves the
        // phone), so the command must go out as WRITE_TYPE_NO_RESPONSE to reach the bottle.
        val writeType = if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        Log.d(TAG, "-> write $hex")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, bytes, writeType)
        } else {
            char.setValue(bytes)
            char.writeType = writeType
            g.writeCharacteristic(char)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            DeviceController.runOnMain {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        resetState()
                        DeviceController.connectionState.value = "Connected"
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        DeviceController.connectionState.value = "Disconnected"
                        DeviceController.discoveredDevices.clear()
                        resetState()
                        // Release this GATT client so its interface doesn't leak in the stack.
                        val g2 = gatt
                        gatt = null
                        g2?.let {
                            it.close()
                            refreshDeviceCache(it)
                        }
                        // Passive background re-establishment: if the link dropped on its own
                        // (device slept / went out of range), reconnect with autoConnect=true so
                        // the platform silently waits for it to return instead of churning.
                        val addr = currentAddress
                        if (!intentionalDisconnect && addr != null) {
                            openGatt(addr, autoConnect = true)
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = g.getService(NOTIFY_SERVICE_UUID)
            if (service == null) {
                Log.w(TAG, "NOTIFY service $NOTIFY_SERVICE_UUID not found; services=${g.services.map { it.uuid }}")
                return
            }
            val char = service.getCharacteristic(NOTIFY_CHAR_UUID)
            if (char == null) {
                Log.w(TAG, "NOTIFY char $NOTIFY_CHAR_UUID not found")
                return
            }
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
            } ?: Log.w(TAG, "CCCD $CCCD_UUID not found on notify char")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (pendingRegistration) {
                // First-time setup: exit factory mode + start registration (blue LED, button press).
                Log.d(TAG, "onDescriptorWrite status=$status; starting registration")
                enqueueCommand(CMD_EXIT_FACTORY_MODE)
                enqueueCommand(CMD_REQUEST_REGISTRATION)
            } else {
                // Notifications are on; kick off the handshake by asking for full bottle data.
                Log.d(TAG, "onDescriptorWrite status=$status; requesting bottle data")
                enqueueCommand(CMD_REQUEST_DATA)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            DeviceController.runOnMain {
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
            Log.d(TAG, "<- notify ${value.toHex()}")
            DeviceController.runOnMain { dispatch(value) }
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
            else -> Log.w(TAG, "dispatch: unhandled packet ${value.toHex()}")
        }
    }

    private fun handleRp(value: ByteArray) {
        if (value.size < 6) return
        val b2 = value[2].toInt() and 0xFF
        val b3 = value[3].toInt() and 0xFF
        val b5 = value[5].toInt() and 0xFF

        // Registration signature response (factory-fresh bottle): prompt for button press.
        if (b5 == 0x20) {
            Log.d(TAG, "RP: signature/registration → press bottle button")
            DeviceController.connectionState.value = "Press the bottle button"
            return
        }

        if (b2 == 0x00 && b3 == 0x31 && value.size > 50) {
            // Vita full snapshot.
            curTemp = value[6].toInt()
            curBattery = value[9].toInt() and 0xFF
            curCharging = value[34].toInt() and 0xFF in intArrayOf(1, 2)
            curTds = beInt(value, 45)
            curVolumePct = volumePct(beInt(value, 49))
            Log.d(TAG, "RP Vita: temp=$curTemp batt=$curBattery charging=$curCharging tds=$curTds vol%=$curVolumePct")
            emitStatus()
            enqueueSyncAndLanguage()
            return
        }
        if (b2 == 0x00 && b3 == 0x27 && value.size > 31) {
            // Boost full snapshot (temp/tds/volume arrive later via RT).
            curBattery = value[6].toInt() and 0xFF
            curCharging = value[31].toInt() and 0xFF in intArrayOf(1, 2)
            Log.d(TAG, "RP Boost: batt=$curBattery charging=$curCharging")
            emitStatus()
            enqueueSyncAndLanguage()
            return
        }
        if (b2 == 0x00 && b3 == 0x0F) {
            // Sync setting acknowledged; now request the water logs.
            Log.d(TAG, "RP: sync ack → request logs")
            enqueueCommand(CMD_REQUEST_LOGS)
            return
        }
        // Water-log availability flag: value[6]==0 means no offline logs to drain.
        if (b5 == 0x06) {
            Log.d(TAG, "RP: water-log availability=${if (value.size > 6) value[6].toInt() and 0xFF else -1}")
            return
        }
        // Registration state: value[6]==2 = user must press the bottle button; ==6 = done.
        if (b5 == 0x1C) {
            if (!pendingRegistration) return
            val step = if (value.size > 6) value[6].toInt() and 0xFF else -1
            Log.d(TAG, "RP: registration step=$step")
            when (step) {
                0x02 -> DeviceController.connectionState.value = "Press the bottle button"
                0x06 -> {
                    // Registration successful (and offline data cleared). Leave registration mode
                    // and proceed to the normal data flow so status/logs start syncing.
                    Log.d(TAG, "RP: registration successful → requesting bottle data")
                    pendingRegistration = false
                    DeviceController.connectionState.value = "Connected"
                    enqueueCommand(CMD_REQUEST_DATA)
                }
            }
        }
    }

    private fun handleRt(value: ByteArray) {
        if (value.size < 7) return
        when (val sel = value[5].toInt() and 0xFF) {
            0x01 -> curTemp = value[6].toInt()
            0x02 -> curBattery = value[6].toInt() and 0xFF
            0x17 -> curCharging = value[6].toInt() and 0xFF in intArrayOf(1, 2)
            0x08 -> {
                // Volume changed (the user drank). The bottle reports the new fill level here;
                // offline drink logs are drained separately during the sync handshake.
                if (value.size < 8) return
                curVolumePct = volumePct(beInt(value, 6))
            }
            0x28 -> {
                if (value.size < 8) return
                curTds = beInt(value, 6)
            }
            0x1C -> {
                // Registration: user confirmed on the bottle (6==3) or it failed (6==4).
                // Ignore once registration is done — the bottle keeps re-sending "confirmed".
                if (!pendingRegistration) return
                val result = value[6].toInt() and 0xFF
                Log.d(TAG, "RT: registration result=$result")
                when (result) {
                    0x03 -> {
                        DeviceController.connectionState.value = "Registering…"
                        // Finalize registration: the bottle replies with "successful" (RP 1C/06),
                        // which then proceeds to the normal data flow. Send once (it repeats 03).
                        if (!registrationClearSent) {
                            registrationClearSent = true
                            enqueueCommand(CMD_CLEAR_OFFLINE_DATA)
                        }
                    }
                    0x04 -> DeviceController.connectionState.value = "Registration failed"
                }
                return
            }
            0xA1 -> {
                Log.d(TAG, "RT: recalibrate result=${value[6].toInt() and 0xFF}")
                return
            }
            else -> {
                Log.d(TAG, "RT: unhandled selector=0x${sel.toString(16)}")
                return
            }
        }
        Log.d(TAG, "RT sel=0x${(value[5].toInt() and 0xFF).toString(16)} → temp=$curTemp batt=$curBattery charging=$curCharging tds=$curTds vol%=$curVolumePct")
        emitStatus()
    }

    private fun handlePtFirst(value: ByteArray) {
        if (value.size < 6 || (value[5].toInt() and 0xFF) != 0x06) return
        expectedLogs = beInt(value, 2) / RECORD_SIZE
        collected.clear()
        parsedRecords = 0
        Log.d(TAG, "logs: expecting $expectedLogs records")
        accumulate(value, 6)
    }

    private fun handlePtContinuation(value: ByteArray) {
        accumulate(value, 2)
    }

    private fun accumulate(value: ByteArray, start: Int) {
        var i = start
        while (i + RECORD_SIZE <= value.size) {
            parsedRecords++
            // Byte 12 is a validity flag; only 0 is a real drink record (matches the WaterH app,
            // which counts non-zero records separately and never builds a log entry for them).
            if ((value[i + 12].toInt() and 0xFF) == 0) {
                collected.add(parseLogRecord(value, i))
            }
            i += RECORD_SIZE
        }
        if (expectedLogs > 0 && parsedRecords >= expectedLogs) {
            Log.d(TAG, "logs: drained parsed=$parsedRecords drinks=${collected.size}")
            collected.forEach { DeviceController.onDrinkLog(it) }
            // Acknowledge/clear the drained logs from the bottle so each is counted once.
            enqueueCommand("525000040306" + toHex(expectedLogs * RECORD_SIZE, 4))
            expectedLogs = 0
            parsedRecords = 0
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
        DeviceController.onBottleStatus(BottleStatus(curTemp, curTds, curBattery, curCharging, curVolumePct))
    }

    private fun parseLogRecord(b: ByteArray, off: Int): HydrationReading {
        val year = 2000 + (b[off].toInt() and 0xFF)
        val month = b[off + 1].toInt() and 0xFF
        val day = b[off + 2].toInt() and 0xFF
        val hour = b[off + 3].toInt() and 0xFF
        val min = b[off + 4].toInt() and 0xFF
        val sec = b[off + 5].toInt() and 0xFF
        val amount = beInt(b, off + 6)
        // TDS is a 2-byte big-endian value at [8..9] (confirmed via parseWaterLog bytecode).
        val tds = beInt(b, off + 8)
        // Raw whole-degree temperature; the WaterH app stores it as temp*10 (tenths).
        val temp = b[off + 10].toInt()
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, day, hour, min, sec)
        return HydrationReading(amount, cal.timeInMillis, tds, temp)
    }

    /** Fill level as a percentage, matching the WaterH app's `volume / 530 * 100`. */
    private fun volumePct(raw: Int): Int = Math.round(raw / 530f * 100).coerceIn(0, 100)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

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
