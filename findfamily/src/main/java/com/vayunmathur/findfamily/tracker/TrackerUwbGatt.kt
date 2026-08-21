package com.vayunmathur.findfamily.tracker

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.vayunmathur.findfamily.uwb.RangingSample
import com.vayunmathur.findfamily.uwb.UwbController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Phone-native **FiRa UWB precision finding** to a bound tracker, over BLE GATT.
 *
 * This is the owner-only "point me to it" last-few-meters step. The design reuses
 * the existing FiRa ranging stack unchanged (`UwbController.openController()` mints
 * the params; `UwbController.stream()` yields distance + AoA); the ONLY thing that
 * differs from the phone-to-phone flow is the transport of the handshake: params go
 * to the tracker over [TrackerBle.UWB_SESSION_CHARACTERISTIC_UUID] instead of the
 * WebSocket `UwbEnvelope`.
 *
 * The phone is the **controller/initiator** and the tracker the controlee/responder.
 * Neither the STS key nor the tracker's UWB address is transmitted — both sides derive
 * them from the bind-time beacon secret ([TrackerUwbKeys]), so the unbonded GATT link
 * carries nothing an attacker could use to range against someone else's tracker.
 */
object TrackerUwbGatt {

    private const val TAG = "TrackerUwbGatt"

    /** FiRa session params handed to the tracker for one ranging session. */
    data class SessionParams(
        val localAddress: ByteArray,   // phone (controller) 2-byte MAC
        val sessionId: Int,
        val channelNumber: Int,
        val preambleIndex: Int,
        // The STS/session key is derived from the bind-time beacon secret on both
        // ends, so it is NOT sent over the air per-find (only channel/slot are).
    )

    /**
     * Encode the per-find params written to the tracker's UWB session characteristic:
     * `[2B localAddress][4B sessionId BE][1B channel][1B preamble]`.
     */
    fun encodeSessionParams(p: SessionParams): ByteArray {
        require(p.localAddress.size == 2) { "localAddress must be 2 bytes" }
        val out = ByteArray(2 + 4 + 1 + 1)
        p.localAddress.copyInto(out, 0)
        out[2] = (p.sessionId ushr 24).toByte()
        out[3] = (p.sessionId ushr 16).toByte()
        out[4] = (p.sessionId ushr 8).toByte()
        out[5] = p.sessionId.toByte()
        out[6] = p.channelNumber.toByte()
        out[7] = p.preambleIndex.toByte()
        return out
    }

    /**
     * Begin a UWB ranging session to the bound tracker at [bleAddress], whose beacon
     * [secret] the owner holds. Opens GATT, writes [encodeSessionParams] so the tracker
     * starts a matching FiRa responder session, and returns the ranging stream.
     *
     * Returns null if the tracker can't be reached or the write fails. [ctrl] is
     * single-use — `UwbController.stop()` shuts down its executor — so pass a fresh
     * instance per find.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    suspend fun startRanging(
        context: Context,
        ctrl: UwbController,
        bleAddress: String,
        secret: ByteArray,
    ): Flow<RangingSample>? {
        val info = ctrl.openController().getOrElse {
            Log.e(TAG, "openController failed", it)
            return null
        }
        val params = SessionParams(
            localAddress = info.localAddress,
            sessionId = info.sessionId,
            channelNumber = info.channelNumber,
            preambleIndex = info.preambleIndex,
        )
        if (!writeSessionParams(context, bleAddress, encodeSessionParams(params))) {
            Log.w(TAG, "could not hand session params to tracker at $bleAddress")
            return null
        }
        // Both ends derive these from the beacon secret rather than exchanging them.
        return ctrl.stream(
            role = UwbController.Role.Initiator,
            localAddress = info.localAddress,
            peerAddress = TrackerUwbKeys.uwbAddress(secret),
            sessionId = info.sessionId,
            sessionKey = TrackerUwbKeys.stsKey(secret, info.sessionId),
            channelNumber = info.channelNumber,
            preambleIndex = info.preambleIndex,
        )
    }

    /**
     * Connect to the tracker at [bleAddress] and write [value] to
     * [TrackerBle.UWB_SESSION_CHARACTERISTIC_UUID]. Returns true iff the write reports
     * success. Mirrors [TrackerProvisioner]'s GATT round-trip; failures surface as false
     * rather than throwing.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun writeSessionParams(
        context: Context,
        bleAddress: String,
        value: ByteArray,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val device = try {
            manager?.adapter?.getRemoteDevice(bleAddress)
        } catch (e: Exception) {
            Log.w(TAG, "getRemoteDevice($bleAddress) failed", e); null
        }
        if (device == null) { cont.resume(false); return@suspendCancellableCoroutine }

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
                    runCatching { g.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    finish(false)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) { finish(false); return }
                val ch = g.getService(TrackerBle.UNPROVISIONED_SERVICE_UUID)
                    ?.getCharacteristic(TrackerBle.UWB_SESSION_CHARACTERISTIC_UUID)
                if (ch == null) { finish(false); return }
                val ok = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(
                            ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        ch.value = value
                        g.writeCharacteristic(ch)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "writeCharacteristic failed", e); false
                }
                if (!ok) finish(false)
            }

            @Deprecated("compat shim for API < 33")
            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
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
}
