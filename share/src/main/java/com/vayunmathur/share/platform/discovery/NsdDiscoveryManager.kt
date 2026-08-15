package com.vayunmathur.share.platform.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.security.SecureRandom
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Modern mDNS service type for Share (BetoCore migration).
 *
 * GMS-analysis findings: Quick Share advertises/browses `_up._tcp` on WiFi-LAN and
 * each advertisement is keyed by a random 16-byte ServiceId (hex). Identity is NOT
 * carried in TXT/endpoint_info — it comes from the BLE Nearby Presence advert.
 *
 * Legacy `_FC9F5ED42C8A._tcp` + plaintext PCP/TXT `n` has been removed (full drop, no fallback).
 */
const val SHARE_SERVICE_TYPE = "_up._tcp"
private const val TAG = "NsdDiscovery"

private fun randomServiceIdHex(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Manages mDNS (DNS-SD) advertisement + discovery for the Share TCP endpoint over _up._tcp.
 *
 * Responsibilities:
 *  - RECEIVE: registerService under _up._tcp with a random 16-byte ServiceId (instance name = hex)
 *    and the WiFi-LAN ServerSocket(0) port. No TXT endpoint_info.
 *  - SEND: discoverServices + resolveService to produce host/port for connect.
 *
 * Identity is resolved from the BLE Presence advert, so receivers must correlate a TCP endpoint
 * discovered here with a BLE peer by serviceId or by UI presence.
 */
class NsdDiscoveryManager(private val context: Context) {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _discoveredDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<NearbyDevice>> = _discoveredDevices.asStateFlow()

    private var advertisedPort: Int = 0
    private var registeredServiceName: String? = null
    private var currentServiceId: String? = null

    /** The ServiceId hex for the current registration, or null if not advertising. */
    val serviceId: String? get() = currentServiceId

    /**
     * Advertise this device as a Share endpoint on [port] under _up._tcp.
     *
     * A fresh random 16-byte ServiceId is generated per registration; the mDNS instance name
     * is that hex string. Safe to call repeatedly — re-advertising tears down the prior registration.
     *
     * @param deviceName human-readable name (kept for logging only; not placed in TXT per new privacy model)
     * @param port TCP port from ServerSocket(0) on WiFi-LAN
     * @return the ServiceId hex that was advertised (for correlation with BLE presence if needed)
     */
    fun advertise(deviceName: String, port: Int): String? {
        val mgr = nsdManager ?: run {
            Log.w(TAG, "NsdManager unavailable — cannot advertise")
            return null
        }
        if (advertisedPort == port && registrationListener != null) return currentServiceId
        unadvertise()
        advertisedPort = port
        val serviceId = randomServiceIdHex()
        currentServiceId = serviceId
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = serviceId
            serviceType = SHARE_SERVICE_TYPE
            setPort(port)
            // No plaintext TXT: no pcp 'n', no endpoint_info per spec.
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                Log.i(TAG, "advertised _up._tcp as ${info.serviceName} (svcId=$serviceId) on port $port name=$deviceName")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed: $errorCode")
                currentServiceId = null
                advertisedPort = 0
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "unregistered ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        try {
            mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "registerService threw", e)
            currentServiceId = null
            advertisedPort = 0
        }
        return serviceId
    }

    fun unadvertise() {
        val mgr = nsdManager ?: return
        val listener = registrationListener ?: return
        try {
            mgr.unregisterService(listener)
        } catch (_: Exception) {
        }
        registrationListener = null
        registeredServiceName = null
        currentServiceId = null
        advertisedPort = 0
    }

    /**
     * Start DNS-SD discovery for _up._tcp and emit [NearbyDevice] values as services are
     * found/lost. Resolve host/port asynchronously. Call from the Send flow's scanning coroutine;
     * cancellation tears down the discovery listener.
     */
    fun discover(): Flow<NearbyDevice> = callbackFlow {
        val mgr = nsdManager ?: run {
            close()
            return@callbackFlow
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "found: ${service.serviceName} type=${service.serviceType}")
                try {
                    mgr.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress
                            val port = info.port
                            val svcName = info.serviceName
                            val dev = NearbyDevice(
                                endpointId = svcName,
                                endpointName = svcName, // will be replaced by BLE presence name when correlated
                                serviceId = svcName,
                                serviceName = svcName,
                                host = host,
                                port = if (port > 0) port else null,
                                source = DiscoverySource.Nsd,
                            )
                            trySend(dev)
                            val current = _discoveredDevices.value.toMutableList()
                            if (current.none { it.endpointId == dev.endpointId }) {
                                current += dev
                            } else {
                                val idx = current.indexOfFirst { it.endpointId == dev.endpointId }
                                current[idx] = dev
                            }
                            _discoveredDevices.value = current
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "resolveService threw for ${service.serviceName}", e)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "lost: ${service.serviceName}")
                _discoveredDevices.value = _discoveredDevices.value.filterNot {
                    it.endpointId == service.serviceName
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "startDiscovery failed $serviceType: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stopDiscovery failed $serviceType: $errorCode")
            }
        }
        discoveryListener = listener
        try {
            mgr.discoverServices(SHARE_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices threw", e)
            close(e)
            return@callbackFlow
        }
        awaitClose {
            try {
                discoveryListener?.let { mgr.stopServiceDiscovery(it) }
            } catch (_: Exception) {
            }
            discoveryListener = null
        }
    }

    /**
     * Convenience: start discovery and collect into [discoveredDevices] until
     * cancelled. Prefer [discover] when the caller wants the flow directly.
     */
    suspend fun startCollectingDiscovery(): Nothing {
        discover().collect { }
        error("unreachable — discover flow completed unexpectedly")
    }

    fun stopDiscovery() {
        val mgr = nsdManager ?: return
        val listener = discoveryListener ?: return
        try {
            mgr.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
        discoveryListener = null
    }

    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyList()
    }

    fun release() {
        unadvertise()
        stopDiscovery()
    }
}
