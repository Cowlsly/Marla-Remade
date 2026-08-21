package com.vayunmathur.cast.platform.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.vayunmathur.cast.domain.CAST_PORT
import com.vayunmathur.cast.domain.CastDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update

private const val TAG = "CastDiscovery"

/** Every Cast receiver - Chromecast, Google TV, Nest speaker - registers this service type. */
const val CAST_SERVICE_TYPE = "_googlecast._tcp"

/** Friendly name, e.g. "Living Room TV". The instance name is a UUID and unreadable. */
private const val TXT_FRIENDLY_NAME = "fn"

/** Hardware model, e.g. "Chromecast Ultra". Shown as the list subtitle. */
private const val TXT_MODEL = "md"

/** The receiver's own UUID. Stable across renames, unlike the instance name. */
private const val TXT_ID = "id"

/** Capability bitmask; see [CastDevice.kind]. */
private const val TXT_CAPABILITIES = "ca"

/** Current status, e.g. "YouTube" or blank when idle. */
private const val TXT_STATUS = "rs"

/**
 * mDNS discovery of Cast receivers on the LAN.
 *
 * Browse-only - nothing is advertised, because a sender is not itself castable. Adapted from
 * `:share`'s `NsdDiscoveryManager`, including the Android 16 trap it documents: with Local
 * Network Protections, `discoverServices` throws `SecurityException` unless
 * `ACCESS_LOCAL_NETWORK` has been granted, and the failure is indistinguishable from "no
 * devices on this network" unless it is surfaced. [localNetworkBlocked] exists so the UI can
 * say which of the two happened instead of showing an empty list forever.
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
     * Browse [CAST_SERVICE_TYPE] and emit each resolved receiver.
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
            manager.discoverServices(CAST_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
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
     * A host is mandatory - the whole point of resolving is to get one - but everything else
     * has a sensible fallback, because a receiver with an odd TXT record is still castable.
     */
    private fun toCastDevice(info: NsdServiceInfo): CastDevice? {
        val host = info.host?.hostAddress ?: return null
        val instance = info.serviceName ?: return null
        val friendlyName = textAttribute(info, TXT_FRIENDLY_NAME)
            ?: textAttribute(info, TXT_MODEL)
            ?: instance
        return CastDevice(
            id = textAttribute(info, TXT_ID) ?: instance,
            instanceName = instance,
            friendlyName = friendlyName,
            host = host,
            port = if (info.port > 0) info.port else CAST_PORT,
            model = textAttribute(info, TXT_MODEL),
            statusText = textAttribute(info, TXT_STATUS),
            capabilities = textAttribute(info, TXT_CAPABILITIES)?.toIntOrNull() ?: 0,
        )
    }

    /**
     * Replace by [CastDevice.id] rather than append.
     *
     * A receiver re-announces itself when its status text changes, and every announcement
     * resolves, so appending would show the same TV three times.
     */
    private fun merge(current: List<CastDevice>, device: CastDevice): List<CastDevice> {
        val index = current.indexOfFirst { it.id == device.id }
        if (index < 0) return current + device
        return current.toMutableList().apply { this[index] = device }
    }
}
