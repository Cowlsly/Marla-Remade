package com.vayunmathur.share.platform.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import android.util.Log
import com.vayunmathur.share.protocol.ShareNative
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "NsdDiscovery"

/**
 * mDNS service type Quick Share registers and browses on WIFI_LAN: `_FC9F5ED42C8A._tcp`.
 *
 * GMS derives it as `String.format("_%s._tcp", hex(SHA-256(serviceId)[0..6]))` where
 * `serviceId` is the literal `"NearbySharing"` — `p000\dsmo.java:119-121` and `:180`,
 * with `drwj.m66219Z` doing SHA-256-then-truncate and `blqe.m21076e` producing
 * *uppercase* hex. The derivation lives in Rust (`ble_adv.rs`) and is asserted by a
 * unit test, so a wrong digest fails the build instead of silently making us invisible.
 *
 * Falls back to the literal only if the native library cannot be reached.
 */
val SHARE_SERVICE_TYPE: String =
    runCatching { ShareNative.nativeMdnsServiceType() }.getOrNull() ?: "_FC9F5ED42C8A._tcp"

/** TXT attribute GMS reads the peer's address from — `p000\dsmo.java:127`, `:550`. */
private const val TXT_IPV4 = "IPv4"

/**
 * TXT attribute carrying the Base64 endpoint-info blob — `p000\dnuw.java:208` writes it and
 * `p000\dnux.java:98-106` requires it (`"Cannot deserialize WifiLanServiceInfo: EndpointInfo
 * is missing"`). The name is the literal `"n"`.
 */
private const val TXT_ENDPOINT_INFO = "n"

/**
 * Base64 flavour GMS uses for both the instance name and the `n` attribute.
 *
 * `p000\bloa.java:29` (`m20888c`, encode), `:53` (`m20891f`, decode instance name) and
 * `:61` (`m20892g`, decode `n`) all pass Android's flag `11`, which is exactly
 * `URL_SAFE or NO_PADDING or NO_WRAP`. One constant so the two directions cannot drift.
 */
private const val GMS_BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

private fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, GMS_BASE64_FLAGS)

private fun decodeBase64(text: String): ByteArray? =
    runCatching { Base64.decode(text, GMS_BASE64_FLAGS) }.getOrNull()

/**
 * mDNS (DNS-SD) advertisement and discovery for the Quick Share TCP endpoint.
 *
 * - RECEIVE: `registerService` under [SHARE_SERVICE_TYPE] with the WIFI_LAN
 *   `ServerSocket(0)` port, the instance name set to the Base64 `WifiLanServiceInfo`, the
 *   endpoint-info blob in the `n` TXT attribute and the local address in `IPv4`.
 * - SEND: `discoverServices` + `resolveService`, decoding both of those back, to produce
 *   host/port and the peer's real display name.
 *
 * Both encodings are byte-for-byte what GMS writes and reads — the instance name per
 * `p000\dnuw.java:175-207` / `p000\dnux.java:66-145`, the endpoint info per
 * `p000\dzqk.java:134-186` / `p000\dzqj.java:11-92` — so a record that fails either check
 * here is one a real device would also have dropped. Codecs live in Rust
 * (`endpoint_info.rs`) and are golden-byte tested on the host; only Base64 is done here,
 * because it is a platform API.
 *
 * This is the medium that matters: **WIFI_LAN is the only one `:share` can accept a
 * connection on**, so a wrong mDNS record means no transfer even when BLE discovery works.
 */
class NsdDiscoveryManager(private val context: Context) {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _discoveredDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<NearbyDevice>> = _discoveredDevices.asStateFlow()

    private var advertisedPort: Int = 0
    private var advertisedEndpointInfo: ByteArray? = null
    private var advertisedInstance: String? = null
    private var registeredServiceName: String? = null

    /** The mDNS instance name of the current registration, or null if not advertising. */
    val instanceName: String? get() = registeredServiceName

    /**
     * Advertise this device as a Quick Share endpoint on [port].
     *
     * [endpointId] must be exactly 4 ASCII characters; it is published as the Base64
     * `WifiLanServiceInfo` instance name. [endpointInfo] is the endpoint-info blob, which
     * goes into the `n` TXT attribute, and [localAddress], when known, into `IPv4`.
     * Safe to call repeatedly — re-advertising tears down the prior registration.
     *
     * Returns the instance name that was registered, or null when the record could not be
     * built: a malformed one is silently invisible, so there is no fallback.
     */
    fun advertise(
        endpointId: String,
        endpointInfo: ByteArray,
        port: Int,
        localAddress: String? = null,
    ): String? {
        val mgr = nsdManager ?: run {
            Log.w(TAG, "NsdManager unavailable — cannot advertise")
            return null
        }
        val serviceInfoBytes = try {
            ShareNative.nativeBuildWifiLanServiceInfo(endpointId)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libshare_nearby unavailable — refusing to advertise a guessed format", e)
            return null
        }
        if (serviceInfoBytes == null) {
            Log.w(TAG, "endpointId '$endpointId' is not 4 ASCII characters — cannot advertise")
            return null
        }
        if (endpointInfo.isEmpty()) {
            Log.w(TAG, "empty endpointInfo — a peer would drop the record")
            return null
        }
        val instance = encodeBase64(serviceInfoBytes)
        // Compare against what was requested, not against `registeredServiceName`: NSD may
        // rename the instance on a conflict, and re-registering every call would churn.
        if (advertisedPort == port &&
            advertisedInstance == instance &&
            advertisedEndpointInfo.contentEquals(endpointInfo)
        ) {
            return registeredServiceName
        }
        unadvertise()
        advertisedPort = port
        advertisedEndpointInfo = endpointInfo
        advertisedInstance = instance
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = instance
            serviceType = SHARE_SERVICE_TYPE
            setPort(port)
            setAttribute(TXT_ENDPOINT_INFO, encodeBase64(endpointInfo))
            if (localAddress != null) setAttribute(TXT_IPV4, localAddress)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                Log.i(
                    TAG,
                    "advertised $SHARE_SERVICE_TYPE as ${info.serviceName} on port $port " +
                        "(endpointId=$endpointId, ${endpointInfo.size}B endpointInfo)",
                )
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed: $errorCode")
                registeredServiceName = null
                advertisedPort = 0
                advertisedEndpointInfo = null
                advertisedInstance = null
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
        } catch (e: SecurityException) {
            // Android 16+ Local Network Protections: ACCESS_LOCAL_NETWORK is missing or was
            // denied. Nothing about the record is wrong; the OS blocks mDNS outright.
            Log.e(TAG, "mDNS blocked — ACCESS_LOCAL_NETWORK not granted, so :share is invisible", e)
            registrationListener = null
            registeredServiceName = null
            advertisedPort = 0
            advertisedEndpointInfo = null
            advertisedInstance = null
            return null
        } catch (e: Exception) {
            Log.w(TAG, "registerService threw", e)
            registrationListener = null
            registeredServiceName = null
            advertisedPort = 0
            advertisedEndpointInfo = null
            advertisedInstance = null
            return null
        }
        return instance
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
        advertisedPort = 0
        advertisedEndpointInfo = null
        advertisedInstance = null
    }

    private fun textAttribute(info: NsdServiceInfo, key: String): String? =
        runCatching { info.attributes[key]?.toString(Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * Turn a resolved record into a [NearbyDevice], or null when it is not a record GMS
     * would accept either.
     *
     * Both required fields are checked: the instance name must decode to a
     * `WifiLanServiceInfo` (`p000\dnux.java:86-118`) and the `n` attribute to an
     * endpoint-info blob (`:98-106`, `p000\dzqj.java:11-92`). The endpoint id and display
     * name come from those, not from the instance name text.
     */
    private fun toNearbyDevice(info: NsdServiceInfo): NearbyDevice? {
        val instance = info.serviceName ?: return null
        if (instance == registeredServiceName || instance == advertisedInstance) {
            // "Wifi LAN discovered service %s, but that's us. Ignoring." — dsmo.java:303-305.
            return null
        }
        val serviceInfoBytes = decodeBase64(instance) ?: run {
            Log.d(TAG, "skipping $instance: instance name is not Base64")
            return null
        }
        val endpointInfoBytes = textAttribute(info, TXT_ENDPOINT_INFO)?.let(::decodeBase64)
        if (endpointInfoBytes == null) {
            Log.d(TAG, "skipping $instance: no usable '$TXT_ENDPOINT_INFO' attribute")
            return null
        }
        val wifiLan = ShareNative.parseWifiLanServiceInfo(serviceInfoBytes) ?: run {
            Log.d(TAG, "skipping $instance: not a WifiLanServiceInfo")
            return null
        }
        val endpointInfo = ShareNative.parseEndpointInfo(endpointInfoBytes) ?: run {
            Log.d(TAG, "skipping $instance: endpoint info would be rejected")
            return null
        }
        // GMS prefers the IPv4 TXT attribute over the resolved host
        // (p000\dsmo.java:127-134), so mirror that order.
        val host = textAttribute(info, TXT_IPV4) ?: info.host?.hostAddress
        val port = info.port
        return NearbyDevice(
            endpointId = wifiLan.endpointId,
            // A contact-only peer publishes no name; it is still connectable.
            endpointName = endpointInfo.deviceName ?: wifiLan.endpointId,
            serviceId = instance,
            serviceName = instance,
            host = host,
            port = if (port > 0) port else null,
            source = DiscoverySource.Nsd,
        )
    }

    /**
     * Start DNS-SD discovery for [SHARE_SERVICE_TYPE] and emit [NearbyDevice] values as
     * services are found. Cancellation tears down the discovery listener.
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
                            val dev = try {
                                toNearbyDevice(info)
                            } catch (e: UnsatisfiedLinkError) {
                                Log.e(TAG, "libshare_nearby unavailable — cannot decode records", e)
                                close(e)
                                return
                            } ?: return
                            trySend(dev)
                            val current = _discoveredDevices.value.toMutableList()
                            val idx = current.indexOfFirst { it.endpointId == dev.endpointId }
                            if (idx >= 0) current[idx] = dev else current += dev
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
                    it.serviceName == service.serviceName
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
        } catch (e: SecurityException) {
            Log.e(TAG, "mDNS browse blocked — ACCESS_LOCAL_NETWORK not granted", e)
            close(e)
            return@callbackFlow
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
