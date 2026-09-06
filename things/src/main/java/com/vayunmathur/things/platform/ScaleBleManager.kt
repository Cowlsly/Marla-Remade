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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Offline BLE manager for the Renpho Elis 1 / Qingniu (Yolanda) scale.
 *
 * Protocol basis: renpho_analysis/OFFLINE_FEASIBILITY.md + YOLANDA_CALC_FORMULAS.md +
 * jadx-out/sources/com/qingniu and com/qn.
 *
 * - GATT services 0000FFE0 / 0000FFF0; notify chars 0000FFE1/0000FFF1, indicate 0000FFE2;
 *   write chars 0000FFE3/0000FFF2 (config, acks) and 0000FFE4 (time, start).
 * - The scale will not stream anything until it is driven: on receiving the 0x12 scale-info it
 *   wants a 0x13 config frame, then a 0x20 time frame (re-sent until acknowledged by 0x21), then
 *   a 0x22 start. Only then does it emit 0x10 weight packets. See ScaleBleServiceManager /
 *   ScaleBleManager / QNDecoderImpl in the decompiled SDK.
 * - Scan-by-name: devices advertise as QN-Scale / QN-Scale1 / RENPHO / Elis (confirmed
 *   from BleConst.DEFAULT_BLE_SCALE_NAME="QN-Scale", DEFAULT_BLE_SCALE_NAME_1="QN-Scale1"
 *   and OFFLINE_FEASIBILITY packet captures). We filter on name prefix.
 * - Scale category and the resistance-encryption flag come from the advertisement's
 *   manufacturer-specific data, exactly as ScaleBleUtils.checkScaleType and
 *   ScaleBleUtils.isUseResistanceEncrypt read them.
 * - Packet decode: QNDecoderImpl.decodeData — c=16 weight at bytes 3..4 (weightRatio 10/100),
 *   sub-command at byte 5 (0/17/18 streaming, 1 and 2 stable, 1 on an eight-electrode scale
 *   is the ten-channel burst); impedance via fourResTwoByte2Int(bytes 6..9) and
 *   eightResTwoByte2Double(kRatio=0.1); c=18 scale-info (weightRatio, units), c=35 stored
 *   history (timestamp bytes 5..8, weight 9..10, impedance 11..14).
 *
 * No wifi/internet/cloud/login — pure on-device BLE + BodyComposition math.
 */
@SuppressLint("MissingPermission")
class ScaleBleManager {

    companion object {
        private const val TAG = "ScaleBle"

        // Primary Qingniu GATT (covers Elis 1)
        val SERVICE_FFE0: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val CHAR_FFE1: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        val CHAR_FFE2: UUID = UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB")
        val CHAR_FFE3: UUID = UUID.fromString("0000FFE3-0000-1000-8000-00805F9B34FB")
        val CHAR_FFE4: UUID = UUID.fromString("0000FFE4-0000-1000-8000-00805F9B34FB")
        // Secondary (FFF0 family) — Holtek firmware collapses every write onto FFF2
        val SERVICE_FFF0: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        val CHAR_FFF1: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
        val CHAR_FFF2: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
        // Battery + Device Info
        val SERVICE_BATTERY: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val CHAR_BATTERY: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /** Name prefixes seen for Qingniu/Renpho scales. */
        val SCALE_NAME_PREFIXES = listOf("QN-Scale", "QN-S3", "RENPHO", "Elis", "Yolanda", "QIANGNIU")

        // CmdBuilder command bytes.
        private const val CMD_CONFIG = 0x13
        private const val CMD_OVER = 0x1F
        private const val CMD_TIME = 0x20
        private const val CMD_START = 0x22
        private const val CMD_USER_SYNC = 0xA0

        /**
         * VA-class scales (ScaleBleUtils.isVaScale) speak a variant of the protocol: the config
         * frame carries no user data, weight sits at bytes 5..6 of the 0x10 frame rather than
         * 3..4, and nothing is reported at all until a user slot is synced with 0xA0.
         */
        private val VA_CATEGORIES = setOf(128, 129, 134, 143)
        private const val VA_SUB_VISIT = 2
        private const val VA_VISITOR_INDEX = 0xFE
        private const val VA_VISITOR_KEY_HI = 0xFF
        private const val VA_VISITOR_KEY_LO = 0xEE
        /** Body-fat algorithm id; we only use the scale's raw impedance, so this is inert. */
        private const val VA_ALGORITHM = 7
        /** 1 = Asia reference range, 2 = rest of world. */
        private const val VA_FAT_GRADE = 1

        /** BleScaleConfig defaults: kilograms, and the scale's display-light interval. */
        private const val UNIT_KG = 1
        private const val LIGHT_INTERVAL = 16

        // Handshake timings lifted from QNDecoderImpl.
        private const val CONFIG_TO_TIME_MS = 300L
        private const val TIME_RETRY_MS = 250L
        private const val TIME_RETRY_LIMIT = 3
        private const val ACK_TO_START_MS = 250L

        /** ScaleBleUtils.checkScaleType: eight-electrode body-composition scale. */
        private const val CATEGORY_EIGHT_ELECTRODE = 127

        /** ScaleBleUtils.checkScaleType's fallback for a plain BLE scale. */
        private const val CATEGORY_DEFAULT = 100

        /**
         * Epoch the scale timestamps its stored measurements against. DecoderConst has a second,
         * UTC+8-shifted constant, but getBaseTime2000YearSeconds() only returns that one for
         * non-Renpho app IDs — the Renpho SDK init uses this value.
         */
        private const val BASE_TIME_2000_SECONDS = 946684800L

        /** QNDecoderImpl.kRatio, fixed for every eight-electrode channel. */
        private const val K_RATIO = 0.1

        /** ConnectionViewModel scans in a bounded window rather than indefinitely. */
        private const val SCAN_TIMEOUT_MS = 20_000L
    }

    data class ScaleBleDevice(val name: String, val address: String)

    private val bluetoothManager = DeviceController.appContext.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager.adapter
    private val scanner get() = adapter.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    // See BleManager: keep connect() idempotent so repeated auto-connect calls don't stack a
    // second GATT client, and drive passive background re-establishment on an unexpected drop.
    private var currentAddress: String? = null
    private var intentionalDisconnect = false
    /** Address we are passively watching for, if any. */
    private var watchAddress: String? = null

    private var weightRatio = 10.0
    /** VA scales scale the weight up by this instead of dividing by [weightRatio]. */
    private var kgWeightRatio = 0.1
    private var isVaScale = false
    private var scaleType = 0
    private var notifyChar: UUID = CHAR_FFE1
    private var indicateChar: UUID? = null
    /** Config frames and per-measurement acks (FFE3, or FFF2 on Holtek). */
    private var configChar: UUID = CHAR_FFE3
    /** Time and start frames (FFE4, falling back to [configChar] when absent). */
    private var bleWriteChar: UUID = CHAR_FFE3
    private var serviceUuid: UUID = SERVICE_FFE0
    /**
     * Holtek firmware exposes the FFF0 family, collapses every write onto FFF2, and waits for its
     * 0x14 hardware-version packet before it will accept the time frame.
     */
    private var isHoltek = false

    // One outstanding GATT write at a time, drained on onCharacteristicWrite.
    private class Command(val char: UUID, val bytes: ByteArray)
    private val commandQueue = ArrayDeque<Command>()
    private var writing = false
    private val descriptorQueue = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()
    private var timeRetries = 0

    // Advertised manufacturer data per address, kept from the scan so that the scale category and
    // the resistance-encryption flag can be resolved once we know which device we are connecting to.
    private val manufacturerData = HashMap<String, ByteArray>()
    private var scaleCategory = 0
    private var useResistanceEncrypt = false

    // For 8-electrode burst reassembly (count/cur at b[6]). burstStarted guards against emitting
    // a reading built from stale channels when the burst's first packet is dropped — BLE
    // notifications are unacknowledged, and a silently wrong body-fat number is worse than none.
    private var burstStarted = false
    private var lf20k = 0.0; private var lf100k = 0.0
    private var rf20k = 0.0; private var rf100k = 0.0
    private var lh20k = 0.0; private var lh100k = 0.0
    private var rh20k = 0.0; private var rh100k = 0.0
    private var t20k = 0.0; private var t100k = 0.0

    private val handler = Handler(Looper.getMainLooper())

    private val scanTimeout = Runnable {
        stopScan()
        if (DeviceController.scaleConnectionState.value == SCALE_SCANNING_STATE) {
            DeviceController.scaleConnectionState.value = "Disconnected"
        }
    }

    /**
     * The scale ignores the time frame until it is ready for it, so the reference SDK just keeps
     * re-sending until the 0x21 acknowledgement lands (or it gives up after three tries).
     */
    private val timeRetry = object : Runnable {
        override fun run() {
            if (timeRetries >= TIME_RETRY_LIMIT) return
            timeRetries++
            enqueue(bleWriteChar, buildCmd(CMD_TIME, *timePayload(System.currentTimeMillis())))
            handler.postDelayed(this, TIME_RETRY_MS)
        }
    }

    private val sendStart = Runnable { enqueue(bleWriteChar, buildCmd(CMD_START)) }

    private fun isScaleName(name: String?): Boolean {
        if (name == null) return false
        return SCALE_NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name
            if (!isScaleName(name)) return
            val addr = result.device.address
            result.scanRecord?.manufacturerSpecificData?.let { data ->
                if (data.size() > 0) data.valueAt(0)?.let {
                    manufacturerData[addr] = it
                    Log.d(TAG, "scan $name company=0x${data.keyAt(0).toString(16)} mfg=${it.toHex()} " +
                        "category=${qnScaleCategory(it)} encryptRes=${qnUsesResistanceEncrypt(qnScaleCategory(it), it)}")
                }
            } ?: Log.d(TAG, "scan $name (no manufacturer data)")
            if (DeviceController.scaleDevices.none { it.address == addr }) {
                DeviceController.scaleDevices.add(ScaleBleDevice(name ?: "Scale", addr))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            DeviceController.runOnMain {
                stopScan()
                DeviceController.scaleConnectionState.value = "Scan failed ($errorCode)"
            }
        }
    }

    fun startScan() {
        DeviceController.scaleDevices.clear()
        manufacturerData.clear()
        DeviceController.scaleScanning.value = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
        DeviceController.scaleConnectionState.value = SCALE_SCANNING_STATE
        handler.removeCallbacks(scanTimeout)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        scanner?.stopScan(scanCallback)
        DeviceController.scaleScanning.value = false
    }

    /**
     * [passive] waits for the scale to wake up instead of trying to reach a powered-off device.
     */
    fun connect(address: String, passive: Boolean = false) {
        if (gatt != null && currentAddress == address) return
        close()
        currentAddress = address
        intentionalDisconnect = false
        if (passive) startWatch(address) else openGatt(address)
    }

    /**
     * A body scale is powered off between weigh-ins, so rather than hold a GATT connection open we
     * watch for its advertisement and connect the moment it appears.
     *
     * This deliberately does not use `connectGatt(autoConnect = true)`: the scale advertises a
     * random static address, and [android.bluetooth.BluetoothAdapter.getRemoteDevice] can only
     * build a public-address device, so a background connection against it never matches. The
     * [ScanResult]'s own device carries the correct address type.
     */
    private fun startWatch(address: String) {
        if (watchAddress == address) return
        stopWatch()
        watchAddress = address
        val filters = listOf(ScanFilter.Builder().setDeviceAddress(address).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        runCatching { scanner?.startScan(filters, settings, watchCallback) }
            .onFailure { Log.e(TAG, "watch scan failed to start", it) }
        Log.d(TAG, "watching for $address to wake up")
        DeviceController.scaleLink.value = DeviceController.LinkState.Waiting
        DeviceController.scaleConnectionState.value = SCALE_WAITING_STATE
    }

    private fun stopWatch() {
        if (watchAddress == null) return
        watchAddress = null
        runCatching { scanner?.stopScan(watchCallback) }
    }

    private val watchCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!result.device.address.equals(watchAddress, ignoreCase = true)) return
            result.scanRecord?.manufacturerSpecificData?.let { data ->
                if (data.size() > 0) data.valueAt(0)?.let { manufacturerData[result.device.address] = it }
            }
            Log.d(TAG, "scale woke up; connecting")
            stopWatch()
            openGattFor(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "watch scan failed error=$errorCode")
            watchAddress = null
            DeviceController.runOnMain {
                DeviceController.scaleConnectionState.value = "Scan failed ($errorCode)"
            }
        }
    }

    private fun openGatt(address: String) = openGattFor(adapter.getRemoteDevice(address))

    @Suppress("DEPRECATION")
    private fun openGattFor(device: BluetoothDevice) {
        stopScan()
        stopWatch()
        val address = device.address
        // The category and encryption flag are only ever advertised, so a reconnect that never
        // scanned has to fall back to what the last scan learned.
        val mfg = manufacturerData[address]
        if (mfg != null) {
            scaleCategory = qnScaleCategory(mfg)
            useResistanceEncrypt = qnUsesResistanceEncrypt(scaleCategory, mfg)
            DeviceController.saveScaleAdvertisedTraits(scaleCategory, useResistanceEncrypt)
        } else {
            scaleCategory = DeviceController.savedScaleCategory() ?: CATEGORY_DEFAULT
            useResistanceEncrypt = DeviceController.savedScaleEncryptsResistance()
        }
        isVaScale = scaleCategory in VA_CATEGORIES
        Log.d(TAG, "connect $address category=$scaleCategory va=$isVaScale encryptRes=$useResistanceEncrypt")
        DeviceController.scaleLink.value = DeviceController.LinkState.Connecting
        DeviceController.scaleConnectionState.value = "Connecting scale..."
        gatt = device.connectGatt(DeviceController.appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        intentionalDisconnect = true
        currentAddress = null
        stopWatch()
        gatt?.disconnect()
    }

    fun close() {
        handler.removeCallbacks(timeRetry)
        handler.removeCallbacks(sendStart)
        stopWatch()
        gatt?.let {
            it.close()
            refreshCache(it)
        }
        gatt = null
    }

    private fun resetPacketState() {
        handler.removeCallbacks(timeRetry)
        handler.removeCallbacks(sendStart)
        commandQueue.clear()
        descriptorQueue.clear()
        writing = false
        timeRetries = 0
        scaleType = 0
        weightRatio = 10.0
        resetBurst()
    }

    private fun resetBurst() {
        burstStarted = false
        lf20k = 0.0; lf100k = 0.0; rf20k = 0.0; rf100k = 0.0
        lh20k = 0.0; lh100k = 0.0; rh20k = 0.0; rh100k = 0.0; t20k = 0.0; t100k = 0.0
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            DeviceController.runOnMain {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        resetPacketState()
                        DeviceController.scaleLink.value = DeviceController.LinkState.Connected
                        DeviceController.scaleConnectionState.value = "Discovering services..."
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        DeviceController.scaleLink.value = DeviceController.LinkState.Waiting
                        DeviceController.scaleConnectionState.value = "Disconnected"
                        DeviceController.scaleDevices.clear()
                        resetPacketState()
                        val g2 = gatt
                        gatt = null
                        g2?.let {
                            it.close()
                            refreshCache(it)
                        }
                        val addr = currentAddress
                        if (!intentionalDisconnect && addr != null) {
                            // The scale powers itself off after each weigh-in, so go back to
                            // watching for it rather than treating this as a failure.
                            startWatch(addr)
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            // Everything below mutates state that dispatch() and the handshake runnables also
            // touch, so keep it all on the main thread.
            DeviceController.runOnMain {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    DeviceController.scaleConnectionState.value = "Service discovery failed ($status)"
                    return@runOnMain
                }
                // Prefer FFE0/FFE1, fall back to FFF0/FFF1 if that's what the firmware exposes.
                val svc = g.getService(SERVICE_FFE0) ?: g.getService(SERVICE_FFF0)
                if (svc == null) {
                    DeviceController.scaleConnectionState.value = "Scale service not found"
                    return@runOnMain
                }
                serviceUuid = svc.uuid
                isHoltek = serviceUuid == SERVICE_FFF0
                val holtek = isHoltek
                logGatt(g)
                val ch = svc.getCharacteristic(if (holtek) CHAR_FFF1 else CHAR_FFE1)
                if (ch == null) {
                    DeviceController.scaleConnectionState.value = "Scale notifying char not found"
                    return@runOnMain
                }
                notifyChar = ch.uuid
                configChar = if (holtek) CHAR_FFF2 else CHAR_FFE3
                bleWriteChar = if (!holtek && svc.getCharacteristic(CHAR_FFE4) != null) CHAR_FFE4 else configChar
                Log.d(TAG, "service=$serviceUuid holtek=$holtek notify=$notifyChar config=$configChar bleWrite=$bleWriteChar")

                descriptorQueue.clear()
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    Log.w(TAG, "notify char $notifyChar has no CCCD; notifications cannot be enabled")
                    DeviceController.scaleConnectionState.value = "Scale CCCD not found"
                } else {
                    descriptorQueue.addLast(cccd to BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
                // FFE2 is an indicate characteristic feeding the same decoder; the reference
                // enables it whenever the firmware exposes it.
                indicateChar = null
                if (!holtek) {
                    svc.getCharacteristic(CHAR_FFE2)?.let { ind ->
                        indicateChar = ind.uuid
                        g.setCharacteristicNotification(ind, true)
                        ind.getDescriptor(CCCD_UUID)?.let {
                            descriptorQueue.addLast(it to BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                        }
                    }
                }
                writeNextDescriptor(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite ${descriptor.characteristic.uuid} status=$status")
            DeviceController.runOnMain {
                if (descriptorQueue.isNotEmpty()) {
                    writeNextDescriptor(g)
                } else {
                    // The scale pushes its 0x12 scale-info unprompted once subscribed; that packet
                    // is what kicks off the config/time/start handshake.
                    DeviceController.scaleConnectionState.value = "Connected — step on scale"
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "onCharacteristicWrite ${char.uuid} status=$status")
            DeviceController.runOnMain {
                writing = false
                writeNext()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            Log.d(TAG, "<- ${characteristic.uuid.short()} ${value.toHex()}")
            if (characteristic.uuid != notifyChar && characteristic.uuid != indicateChar) return
            if (value.isEmpty()) return
            DeviceController.runOnMain { dispatch(value) }
        }

        // The (gatt, characteristic, value) overload only exists from API 33; below that the
        // platform delivers notifications through this one, so without it nothing arrives on
        // Android 12/12L (minSdk is 31).
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicChanged(g, characteristic, characteristic.value ?: return)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeNextDescriptor(g: BluetoothGatt) {
        val (desc, value) = descriptorQueue.removeFirstOrNull() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(desc, value)
        } else {
            desc.setValue(value)
            g.writeDescriptor(desc)
        }
    }

    private fun enqueue(char: UUID, bytes: ByteArray) {
        commandQueue.addLast(Command(char, bytes))
        if (!writing) writeNext()
    }

    @Suppress("DEPRECATION")
    private fun writeNext() {
        val g = gatt
        if (g == null || commandQueue.isEmpty()) {
            writing = false
            return
        }
        val cmd = commandQueue.removeFirst()
        val ch = g.getService(serviceUuid)?.getCharacteristic(cmd.char)
        if (ch == null) {
            Log.w(TAG, "write char ${cmd.char} not found; dropping ${cmd.bytes.toHex()}")
            writing = false
            return
        }
        writing = true
        val writeType = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        Log.d(TAG, "-> ${cmd.char.short()} ${cmd.bytes.toHex()} type=$writeType")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, cmd.bytes, writeType)
        } else {
            ch.setValue(cmd.bytes)
            ch.writeType = writeType
            g.writeCharacteristic(ch)
        }
    }

    /** Dump the discovered GATT table once, so an unexpected layout is visible in a bug report. */
    private fun logGatt(g: BluetoothGatt) {
        for (svc in g.services) {
            Log.d(TAG, "svc ${svc.uuid.short()}")
            for (c in svc.characteristics) {
                val descriptors = c.descriptors.joinToString(",") { it.uuid.short() }
                Log.d(TAG, "  chr ${c.uuid.short()} props=0x${c.properties.toString(16)} desc=[$descriptors]")
            }
        }
    }

    private fun UUID.short(): String = toString().substring(4, 8)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** CmdBuilder.buildCmd: `[cmd, totalLen, scaleType, ...payload, checksum]`. */
    private fun buildCmd(cmd: Int, vararg payload: Int): ByteArray =
        buildFrame(cmd, scaleType, *payload)

    /** Byte 2 is the scale type for most commands, but a sub-command for 0xA0. */
    private fun buildFrame(cmd: Int, arg: Int, vararg payload: Int): ByteArray {
        val out = ByteArray(payload.size + 4)
        out[0] = cmd.toByte()
        out[1] = out.size.toByte()
        out[2] = arg.toByte()
        for (i in payload.indices) out[i + 3] = payload[i].toByte()
        var sum = 0
        for (i in 0 until out.size - 1) sum += out[i].toInt()
        out[out.size - 1] = sum.toByte()
        return out
    }

    /** CmdBuilder.builderTimeData: seconds since the 2000 epoch, little-endian. */
    private fun timePayload(millis: Long): IntArray {
        val seconds = millis / 1000 - BASE_TIME_2000_SECONDS
        return IntArray(4) { ((seconds shr (it * 8)) and 0xFF).toInt() }
    }

    private fun dispatch(value: ByteArray) {
        when (value[0].toInt() and 0xFF) {
            16 -> if (isVaScale) handleVaMeasure(value) else handleMeasure(value)
            18 -> handleScaleInfo(value)
            20 -> {
                // Holtek firmware withholds its readiness until this hardware-version packet, and
                // only then accepts the time frame.
                if (isHoltek) sendTimeSync()
            }
            33 -> {
                handler.removeCallbacks(timeRetry)
                if (isVaScale) {
                    // A VA scale reports nothing until it has a user slot to attribute it to.
                    sendVisitorUser()
                } else {
                    Log.d(TAG, "time frame acknowledged; starting measurement")
                    handler.removeCallbacks(sendStart)
                    handler.postDelayed(sendStart, ACK_TO_START_MS)
                }
            }
            // Stored-record replay: the VA layout differs from the classic one.
            35 -> if (isVaScale) handleVaStored(value) else handleStored(value)
            0xA1 -> handleUserSyncResult(value)
        }
    }

    /**
     * Register as the transient "visitor" slot. This is what QNBleApi.connectDevice does, and it
     * avoids consuming one of the scale's eight persistent user slots.
     */
    private fun sendVisitorUser() {
        val profile = DeviceController.scaleProfile.value
        val gender = if (profile.sex == Sex.Male) 0 else 1
        val age = profile.age.coerceIn(6, 80)
        val heightMm = (profile.heightCm.coerceIn(40.0, 240.0) * 10).toInt()
        Log.d(TAG, "sync visitor user: gender=$gender age=$age heightMm=$heightMm")
        enqueue(
            bleWriteChar,
            buildFrame(
                CMD_USER_SYNC, VA_SUB_VISIT,
                VA_VISITOR_INDEX, VA_VISITOR_KEY_HI, VA_VISITOR_KEY_LO,
                gender, age, (heightMm shr 8) and 0xFF, heightMm and 0xFF,
                VA_ALGORITHM, VA_FAT_GRADE,
            ),
        )
    }

    private fun handleUserSyncResult(v: ByteArray) {
        if (v.size < 5) return
        val sub = v[2].toInt() and 0xFF
        val index = v[3].toInt() and 0xFF
        val result = v[4].toInt() and 0xFF
        Log.d(TAG, "user sync result sub=$sub index=$index result=$result")
        if (sub != VA_SUB_VISIT || result != 1) return
        // Ask for the stored records. Bit 0 is the visitor/unattributed bucket, which is where a
        // weigh-in taken with no phone present lands; bits 1..8 are other household members'
        // slots, which we deliberately do not claim as our own.
        enqueue(bleWriteChar, buildCmd(CMD_START, 0x00, 0x01))
    }

    /**
     * A measurement the scale buffered while no phone was connected. Draining these is how a
     * weigh-in done without your phone still reaches Health Connect.
     */
    private fun handleVaStored(v: ByteArray) {
        if (v.size < 18) return
        val total = v[3].toInt() and 0xFF
        if (total == 0) {
            Log.d(TAG, "no stored records")
            return
        }
        val index = v[4].toInt() and 0xFF
        // Timestamp is little-endian here while weight and impedance below are big-endian; that
        // asymmetry is in the reference decoder, not a mistake.
        var seconds = 0L
        for (i in 0 until 4) seconds = seconds or ((v[i + 6].toLong() and 0xFF) shl (i * 8))
        val measuredAt = (BASE_TIME_2000_SECONDS + seconds) * 1000L
        val now = System.currentTimeMillis()
        if (now < measuredAt || now - measuredAt > 365L * 24 * 60 * 60 * 1000) {
            Log.d(TAG, "stored record $index/$total timestamp implausible; dropped")
            return
        }
        val weight = decodeWeightByMultiplication(twoByteInt(v[10], v[11]), kgWeightRatio)
        if (weight <= 0) return
        Log.d(TAG, "stored record $index/$total user=${v[5].toInt() and 0xFF} weight=$weight")
        DeviceController.onScaleHistory(
            weightKg = weight,
            r50 = fourResTwoByte2Int(v[12], v[13]),
            r500 = fourResTwoByte2Int(v[14], v[15]),
            measuredAtMillis = measuredAt,
        )
    }

    /** VA 0x10 frame: user index at 3, state at 4, weight at 5..6, impedance at 7..10. */
    private fun handleVaMeasure(v: ByteArray) {
        if (v.size < 7) return
        val state = v[4].toInt() and 0xFF
        val weight = decodeWeightByMultiplication(twoByteInt(v[5], v[6]), kgWeightRatio)
        when (state) {
            // 0 = settling, 1 = weight locked, 18 = reading heart rate.
            0, 1, 18 -> if (weight > 0) DeviceController.onScaleRealtimeWeight(weight)
            2 -> {
                enqueue(configChar, buildCmd(CMD_OVER, 0x10))
                if (v.size < 11) {
                    DeviceController.onScaleMeasurement(weight, 0, 0)
                    return
                }
                DeviceController.onScaleMeasurement(
                    weightKg = weight,
                    r50 = fourResTwoByte2Int(v[7], v[8]),
                    r500 = fourResTwoByte2Int(v[9], v[10]),
                )
            }
        }
    }

    private fun handleScaleInfo(v: ByteArray) {
        // scaleType is echoed back in every command we send, so read it before the length check.
        if (v.size >= 3) scaleType = v[2].toInt() and 0xFF
        // The reference decoder discards anything shorter than this before reading its own
        // version/precision bytes, so a truncated info packet is not trusted for the ratio either.
        if (v.size < 15) return
        weightRatio = if ((v[10].toInt() and 0x01) == 1) 100.0 else 10.0
        kgWeightRatio = if ((v[10].toInt() and 0x01) == 1) 0.01 else 0.1
        // Units etc. available at v[10] bits, v[16] lbPrecision, v[17] unit mask.
        // We keep ratio for weight decode; other bytes inform display only.
        DeviceController.scaleConnectionState.value = "Connected — step on scale"
        startHandshake()
    }

    /**
     * Drive the scale into streaming mode: config frame, then a time frame repeated until it
     * answers 0x21, then the start command. Until this runs the scale reports nothing at all.
     */
    private fun startHandshake() {
        val profile = DeviceController.scaleProfile.value
        val height = profile.heightCm.toInt().coerceIn(60, 220)
        val age = profile.age.coerceIn(6, 80)
        // The config frame inverts the sex encoding used everywhere else in the SDK.
        val gender = if (profile.sex == Sex.Male) 0 else 1
        Log.d(TAG, "handshake: scaleType=$scaleType h=$height age=$age gender=$gender holtek=$isHoltek va=$isVaScale")
        if (isVaScale) {
            // The VA config frame carries display settings only; the profile goes in 0xA0 instead.
            enqueue(configChar, buildCmd(CMD_CONFIG, UNIT_KG, LIGHT_INTERVAL, 0, 0, 0))
        } else {
            enqueue(configChar, buildCmd(CMD_CONFIG, UNIT_KG, LIGHT_INTERVAL, height, age, gender))
        }
        timeRetries = 0
        handler.removeCallbacks(timeRetry)
        // Holtek waits for its own 0x14 packet before it will take the time frame.
        if (!isHoltek) handler.postDelayed(timeRetry, CONFIG_TO_TIME_MS)
    }

    private fun sendTimeSync() {
        timeRetries = 0
        handler.removeCallbacks(timeRetry)
        handler.post(timeRetry)
    }

    private fun handleMeasure(v: ByteArray) {
        if (v.size < 6) return
        val c2 = v[5].toInt() and 0xFF
        val weight = decodeWeight(twoByteInt(v[3], v[4]), weightRatio)

        when {
            // Streaming weight while the user is still settling.
            c2 == 0 || c2 == 17 || c2 == 18 ->
                if (weight > 0) DeviceController.onScaleRealtimeWeight(weight)

            // Eight-electrode scales stream ten channels across two of these packets instead.
            c2 == 1 && scaleCategory == CATEGORY_EIGHT_ELECTRODE -> handleEightElectrode(v, weight)

            // Stable weight plus the four-electrode dual-frequency impedance pair. Byte 10 also
            // carries heart rate on c2 == 2, which this app has nowhere to put.
            c2 == 1 || c2 == 2 -> {
                if (v.size < 10) return
                enqueue(configChar, buildCmd(CMD_OVER, 0x10))
                DeviceController.onScaleMeasurement(
                    weightKg = weight,
                    r50 = fourResTwoByte2Int(v[6], v[7]),
                    r500 = fourResTwoByte2Int(v[8], v[9]),
                )
            }
        }
    }

    /** Ten impedance channels arrive across a two-packet burst; byte 6 is count:current. */
    private fun handleEightElectrode(v: ByteArray, weight: Double) {
        if (v.size < 17) return
        val b6 = v[6].toInt() and 0xFF
        val count = (b6 shr 4) and 0x0F
        val current = b6 and 0x0F
        enqueue(configChar, buildCmd(CMD_OVER, 0x10, b6))
        if (count != current) {
            lf20k = eightDouble(v[7], v[8])
            lf100k = eightDouble(v[9], v[10])
            rf20k = eightDouble(v[11], v[12])
            rf100k = eightDouble(v[13], v[14])
            lh20k = eightDouble(v[15], v[16])
            burstStarted = true
            return
        }
        if (!burstStarted) return
        lh100k = eightDouble(v[7], v[8])
        rh20k = eightDouble(v[9], v[10])
        rh100k = eightDouble(v[11], v[12])
        t20k = eightDouble(v[13], v[14])
        t100k = eightDouble(v[15], v[16])
        DeviceController.onScaleMeasurement(
            weightKg = weight,
            r50 = (lh20k + rh20k).toInt(),
            r500 = (lh100k + rh100k).toInt(),
            segmental = SegmentalImpedance(
                rh20 = rh20k, lh20 = lh20k, t20 = t20k, rf20 = rf20k, lf20 = lf20k,
                rh100 = rh100k, lh100 = lh100k, t100 = t100k, rf100 = rf100k, lf100 = lf100k,
            ),
        )
        resetBurst()
    }

    /** Measurements the scale buffered while the phone was away, replayed on connect. */
    private fun handleStored(v: ByteArray) {
        // Eight-electrode scales replay across a paired-packet format we don't reassemble.
        if (scaleCategory == CATEGORY_EIGHT_ELECTRODE || v.size < 15) return
        val weight = decodeWeight(twoByteInt(v[9], v[10]), weightRatio)
        if (weight <= 0) return
        // The timestamp is little-endian even though weight and impedance in the same packet are
        // big-endian; that asymmetry is in the reference decoder, not a mistake here.
        var seconds = 0L
        for (i in 0 until 4) seconds = seconds or ((v[i + 5].toLong() and 0xFF) shl (i * 8))
        val measuredAt = (BASE_TIME_2000_SECONDS + seconds) * 1000L
        val now = System.currentTimeMillis()
        // The scale's clock free-runs, so drop replays dated in the future or over a year back.
        if (now < measuredAt || now - measuredAt > 365L * 24 * 60 * 60 * 1000) return
        DeviceController.onScaleHistory(
            weightKg = weight,
            r50 = fourResTwoByte2Int(v[11], v[12]),
            r500 = fourResTwoByte2Int(v[13], v[14]),
            measuredAtMillis = measuredAt,
        )
    }

    /**
     * ScaleBleUtils.checkScaleType, narrowed to the plain BLE scales this app talks to: the
     * category is a marker byte in the advertisement's manufacturer-specific data.
     */
    private fun qnScaleCategory(mfg: ByteArray?): Int {
        if (mfg == null || mfg.size <= 11) return CATEGORY_DEFAULT
        return when (mfg[11].toInt() and 0xFF) {
            0x21 -> 101
            0x30, 0x31 -> 130
            0x50 -> CATEGORY_EIGHT_ELECTRODE
            0x51, 0x52 -> 134
            0x60, 0x65, 0x66 -> 128
            0x61, 0x62 -> 129
            0x70 -> 135
            0x71 -> 142
            0x80 -> 143
            else -> CATEGORY_DEFAULT
        }
    }

    /** ScaleBleUtils.isUseResistanceEncrypt. */
    private fun qnUsesResistanceEncrypt(category: Int, mfg: ByteArray?): Boolean {
        if (mfg == null) return false
        return when (category) {
            128, 129, 134, 143 -> mfg.size > 15 && ((mfg[15].toInt() shr 2) and 1) == 1
            CATEGORY_DEFAULT, 127, 130, 135, 142 -> mfg.size > 12 && (mfg[12].toInt() and 1) == 1
            else -> false
        }
    }

    // Copy of MeasureDecoder helpers so we don't depend on the QN SDK.
    private fun twoByteInt(hi: Byte, lo: Byte): Int =
        ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)

    /**
     * MeasureDecoder.resistanceCrypt: scales that advertise the encryption bit send each
     * impedance byte with bits 3/5 and 0/4 swapped.
     */
    private fun resistanceCrypt(b: Byte): Int {
        val raw = b.toInt() and 0xFF
        return if (useResistanceEncrypt) swapBit(0, 4, swapBit(3, 5, raw)) else raw
    }

    private fun swapBit(a: Int, b: Int, value: Int): Int {
        val bitA = (value shr a) and 1
        if (bitA == ((value shr b) and 1)) return value
        return if (bitA == 0) ((1 shl a) or value) and (1 shl b).inv()
        else ((1 shl b) or value) and (1 shl a).inv()
    }

    private fun fourResTwoByte2Int(b1: Byte, b2: Byte): Int {
        val v = (resistanceCrypt(b1) shl 8) or resistanceCrypt(b2)
        return if (v >= 60000) 0 else v
    }

    private fun eightDouble(b1: Byte, b2: Byte): Double =
        ((resistanceCrypt(b1) shl 8) or resistanceCrypt(b2)) * K_RATIO

    private fun decodeWeight(raw: Int, ratio: Double): Double {
        var w = raw.toDouble() / ratio
        while (w > 300.0) w /= 10.0
        return w
    }

    /** MeasureDecoder.decodeWeightByMultiplication, used by the VA frame layout. */
    private fun decodeWeightByMultiplication(raw: Int, ratio: Double): Double {
        var w = raw * ratio
        while (w > 300.0) w /= 10.0
        return w
    }

    private fun refreshCache(g: BluetoothGatt) {
        runCatching { g.javaClass.getMethod("refresh").invoke(g) }
    }
}

private const val SCALE_SCANNING_STATE = "Scanning scales..."
private const val SCALE_WAITING_STATE = "Waiting for scale"
