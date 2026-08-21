package com.vayunmathur.cast.platform.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.protocol.MACAST_SERVICE_TYPE
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update

private const val TAG = "CastDiscovery"

/** Friendly name, e.g. "Living Room TV". */
private const val TXT_FRIENDLY_NAME = "fn"

/** The protocol version the receiver speaks. */
private const val TXT_PROTOCOL_VERSION = "pv"

/** The receiver's own stable id. Stable across renames, unlike the instance name. */
private const val TXT_ID = "id"

/**
 * mDNS discovery of MA Cast receivers on the LAN.
 *
 * Browse-only - nothing is advertised, because a phone is not itself castable. Retargeted from
 * `_googlecast._tcp` to [MACAST_SERVICE_TYPE] rather than rewritten, because the shape was the part
 * that was hard: the `callbackFlow` + `DiscoveryListener` pairing, `_devices.update {}` because resolve
 * callbacks land on several threads, and above all [localNetworkBlocked] - on Android 16 a missing
 * `ACCESS_LOCAL_NETWORK` grant makes `discoverServices` throw `SecurityException`, and that is
 * otherwise indistinguishable from an empty network.
 */
class CastDiscoveryManager(context: Context) {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    /** True when the platform refused the browse, not when the network is simply empty. */
    private val _localNetworkBlocked = MutableStateFlow(false)
    val localNetworkBlocked: StateFlow<Boolean> = _localNetworkBlocked.asStateFlow()

    /**
     * Browse [MACAST_SERVICE_TYPE] and emit each resolved receiver.
     *
     * Cancelling the collection stops the browse. Devices also accumulate in [devices], which
     * is what the list observes, so a caller that only wants the state can collect and drop.
     */
    fun discover(): Flow<CastDevice> = callbackFlow {
        val manager = nsdManager ?: run {
            Log.w(TAG, "NsdManager unavailable - cannot browse for receivers")
            close()
            return@callbackFlow
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _localNetworkBlocked.value = false
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                try {
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val device = toCastDevice(info) ?: return
                            trySend(device)
                            // update, not value =: resolve callbacks arrive on several threads
                            // and a read-modify-write would drop devices found at once.
                            _devices.update { merge(it, device) }
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "resolveService threw for ${service.serviceName}", e)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // Only the instance name is available here, so match on it rather than on the
                // UUID; the two are one-to-one for the lifetime of a registration.
                val name = service.serviceName ?: return
                _devices.update { devices -> devices.filterNot { it.instanceName == name } }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "startDiscovery failed: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stopDiscovery failed: $errorCode")
            }
        }
        discoveryListener = listener
        try {
            manager.discoverServices(MACAST_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: SecurityException) {
            // Android 16+ Local Network Protections. Nothing is wrong with the request; the
            // OS blocks mDNS outright until ACCESS_LOCAL_NETWORK is granted.
            Log.e(TAG, "mDNS browse blocked - ACCESS_LOCAL_NETWORK not granted", e)
            _localNetworkBlocked.value = true
            discoveryListener = null
            close()
            return@callbackFlow
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices threw", e)
            discoveryListener = null
            close(e)
            return@callbackFlow
        }
        awaitClose {
            try {
                discoveryListener?.let { manager.stopServiceDiscovery(it) }
            } catch (_: Exception) {
            }
            discoveryListener = null
        }
    }

    fun clear() {
        _devices.value = emptyList()
    }

    private fun textAttribute(info: NsdServiceInfo, key: String): String? =
        runCatching { info.attributes[key]?.toString(Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * Turn a resolved record into a [CastDevice], or null when it is not usable.
     *
     * A host and a port are both mandatory - the receiver binds an ephemeral control port, so unlike
     * Cast's fixed 8009 there is no sensible default to fall back to. The name has one, because a TV
     * with an odd TXT record is still castable.
     */
    private fun toCastDevice(info: NsdServiceInfo): CastDevice? {
        val host = info.host?.hostAddress ?: return null
        val instance = info.serviceName ?: return null
        val port = info.port.takeIf { it > 0 } ?: return null
        return CastDevice(
            id = textAttribute(info, TXT_ID) ?: instance,
            instanceName = instance,
            friendlyName = textAttribute(info, TXT_FRIENDLY_NAME) ?: instance,
            host = host,
            port = port,
            protocolVersion = textAttribute(info, TXT_PROTOCOL_VERSION)?.toIntOrNull() ?: 0,
        )
    }

    /**
     * Replace by [CastDevice.id] rather than append.
     *
     * A receiver re-announces itself whenever its record changes, and every announcement
     * resolves, so appending would show the same TV three times.
     */
    private fun merge(current: List<CastDevice>, device: CastDevice): List<CastDevice> {
        val index = current.indexOfFirst { it.id == device.id }
        if (index < 0) return current + device
        return current.toMutableList().apply { this[index] = device }
    }
}
