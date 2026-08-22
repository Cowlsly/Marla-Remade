package com.vayunmathur.share.platform.discovery.apple

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val TAG = "AirDropProbe"

/**
 * AirDrop's DNS-SD service type.
 *
 * Apple publishes this on the peer-to-peer link, not on the infrastructure network. It is
 * browsed here anyway, on `wlan0`, precisely to test that claim: if a record ever shows up,
 * AirDrop is reachable without a proprietary link layer and this whole question is settled the
 * easy way.
 */
private const val AIRDROP_SERVICE_TYPE = "_airdrop._tcp"

/**
 * Other Apple service types worth browsing, as corroboration.
 *
 * `_companion-link._tcp` and `_rdlink._tcp` back Continuity and Sidecar. If those resolve on
 * the LAN while `_airdrop._tcp` never does, that is evidence the silence is specific to
 * AirDrop's link layer rather than a general mDNS failure on this device — which is the
 * difference between a finding and a broken probe.
 */
private val APPLE_SERVICE_TYPES = listOf(
    AIRDROP_SERVICE_TYPE,
    "_companion-link._tcp",
    "_rdlink._tcp",
    "_apple-mobdev2._tcp",
)

/** One mDNS record seen on the infrastructure network. */
data class MdnsSighting(
    val serviceType: String,
    val serviceName: String,
    val host: String?,
    val port: Int?,
    val resolved: Boolean,
)

/**
 * Browse arbitrary DNS-SD service types on the normal Wi-Fi interface.
 *
 * Separate from [com.vayunmathur.share.platform.discovery.NsdDiscoveryManager], which is
 * hard-wired to the Quick Share type and applies Quick Share's record validation. This one
 * validates nothing and reports whatever it sees, because for a probe an unparseable record is
 * still a result.
 */
class MdnsBrowser(private val context: Context) {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _found = MutableStateFlow<Map<String, MdnsSighting>>(emptyMap())
    val found: StateFlow<Map<String, MdnsSighting>> = _found.asStateFlow()

    /**
     * Forget every record seen so far.
     *
     * Called once per probe run rather than per [browse] call, because all the service types
     * share one map and a per-browse reset would wipe its siblings.
     */
    fun reset() {
        _found.value = emptyMap()
    }

    fun browse(serviceType: String): Flow<MdnsSighting> = callbackFlow {
        val mgr = nsdManager ?: run {
            Log.w(TAG, "NsdManager unavailable")
            close()
            return@callbackFlow
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS browse started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                val name = service.serviceName ?: return
                val key = "$serviceType|$name"
                val unresolved = MdnsSighting(serviceType, name, null, null, resolved = false)
                _found.value = _found.value + (key to unresolved)
                Log.i(TAG, "mDNS FOUND $serviceType instance=\"$name\"")
                trySend(unresolved)
                try {
                    mgr.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "mDNS resolve failed for $name: $errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val sighting = MdnsSighting(
                                serviceType = serviceType,
                                serviceName = info.serviceName ?: name,
                                host = info.host?.hostAddress,
                                port = info.port.takeIf { it > 0 },
                                resolved = true,
                            )
                            _found.value = _found.value + (key to sighting)
                            Log.i(
                                TAG,
                                "mDNS RESOLVED $serviceType \"$name\" -> " +
                                    "${sighting.host}:${sighting.port} attrs=${info.attributes.keys}",
                            )
                            trySend(sighting)
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "resolveService threw for $name", e)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val name = service.serviceName ?: return
                _found.value = _found.value - "$serviceType|$name"
                Log.d(TAG, "mDNS lost $serviceType \"$name\"")
            }

            override fun onDiscoveryStopped(type: String) {
                Log.d(TAG, "mDNS browse stopped: $type")
            }

            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                Log.w(TAG, "mDNS startDiscovery failed $type: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                Log.w(TAG, "mDNS stopDiscovery failed $type: $errorCode")
            }
        }
        try {
            startDiscovery(mgr, serviceType, listener)
        } catch (e: SecurityException) {
            // Android 16+ Local Network Protections. Distinguish this in the log: a blocked
            // browse produces the same empty result as a browse that found nothing.
            Log.e(TAG, "mDNS browse of $serviceType blocked — ACCESS_LOCAL_NETWORK not granted", e)
            close(e)
            return@callbackFlow
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices($serviceType) threw", e)
            close(e)
            return@callbackFlow
        }
        awaitClose {
            try {
                mgr.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Start discovery **bound to the Wi-Fi network**, not to the default one.
     *
     * The three-argument [NsdManager.discoverServices] uses the process's default network. On a
     * phone with a SIM the default network is usually cellular even while Wi-Fi is associated,
     * and mDNS on a cellular interface fails immediately with `FAILURE_INTERNAL_ERROR` (0) — no
     * exception, just a callback. That is exactly the failure this probe hit on first run: the
     * device was on `mathurs-5` at 192.168.0.76 while routing through `rmnet16`, so all four
     * browses died before reaching the LAN and the "no `_airdrop._tcp`" result was meaningless.
     *
     * Passing a [NetworkRequest] for [NetworkCapabilities.TRANSPORT_WIFI] makes the platform run
     * the browse on the Wi-Fi network regardless of which one is default. The overload needs
     * API 33; below that the default-network call is the only option.
     */
    private fun startDiscovery(
        mgr: NsdManager,
        serviceType: String,
        listener: NsdManager.DiscoveryListener,
    ) {
        if (Build.VERSION.SDK_INT >= 33) {
            val wifiOnly = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            mgr.discoverServices(
                serviceType,
                NsdManager.PROTOCOL_DNS_SD,
                wifiOnly,
                // The context's main executor rather than a new single-thread one: this is called
                // once per service type, and an executor created here would leak a thread per
                // browse with nothing owning its shutdown. The callbacks only log and update a
                // StateFlow.
                context.mainExecutor,
                listener,
            )
        } else {
            mgr.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }
}

/**
 * What every observable transport saw at one instant.
 *
 * [airDropBeacons] is the subset of [appleBeacons] carrying AirDrop's `0x05` record, i.e. the
 * Apple devices that currently have a share sheet open.
 */
data class AirDropProbeReport(
    val appleBeacons: List<AppleBeaconSighting> = emptyList(),
    val p2p: P2pObservations = P2pObservations(),
    val mdns: List<MdnsSighting> = emptyList(),
) {
    val airDropBeacons: List<AppleBeaconSighting> get() = appleBeacons.filter { it.isAirDropping }

    val sawAirDropBeacon: Boolean get() = airDropBeacons.isNotEmpty()

    val sawAirDropOverMdns: Boolean
        get() = mdns.any { it.serviceType.startsWith(AIRDROP_SERVICE_TYPE) }

    /**
     * Peers that are plausibly the beaconing iPhone.
     *
     * Deliberately every peer, not a filtered subset. A BLE advert uses a rotating resolvable
     * private address and Wi-Fi Direct reports a different interface MAC, so the two sightings
     * of one physical phone **cannot be correlated by address** — only by the fact that they
     * appeared at the same time. Any filter here would be a guess dressed up as a match.
     */
    val candidateP2pPeers: List<P2pPeer> get() = p2p.peers.values.toList()

    /**
     * A plain-language reading of the evidence.
     *
     * Phrased as what was observed rather than as a conclusion about Apple's design, because
     * this probe can only ever show what is reachable *from this Android device*. "Not visible
     * over Wi-Fi Direct" is consistent with a proprietary link layer, but it is also
     * consistent with a driver or permission problem on this handset — hence the explicit
     * unavailability reporting.
     */
    fun summary(): String = buildString {
        if (!sawAirDropBeacon) {
            appendLine("No AirDrop beacon in range. Open the share sheet on the iPhone and keep it open.")
            if (appleBeacons.isNotEmpty()) {
                appendLine("(${appleBeacons.size} Apple device(s) beaconing, none with an AirDrop record.)")
            }
            return@buildString
        }
        appendLine("AirDrop beacon seen from ${airDropBeacons.size} device(s) — a share sheet is open nearby.")
        airDropBeacons.forEach { b ->
            val hashes = b.airDrop?.populatedHashes()?.joinToString(", ").orEmpty()
            appendLine("  BLE ${b.address} rssi=${b.rssi} contactHashes=[$hashes]")
        }
        val reason = p2p.unavailableReason
        when {
            reason != null -> appendLine("Wi-Fi Direct: could not probe — $reason")
            p2p.peers.isEmpty() -> appendLine("Wi-Fi Direct: 0 peers while AirDrop is beaconing.")
            else -> {
                appendLine("Wi-Fi Direct: ${p2p.peers.size} peer(s) visible:")
                candidateP2pPeers.forEach { appendLine("  P2P \"${it.deviceName}\" ${it.deviceAddress} ${it.statusName}") }
            }
        }
        if (p2p.services.isEmpty()) {
            appendLine("Wi-Fi Direct DNS-SD: no services published by any peer.")
        } else {
            appendLine("Wi-Fi Direct DNS-SD: ${p2p.services.size} service record(s):")
            p2p.services.values.forEach {
                appendLine("  ${it.instanceName} ${it.registrationType} from ${it.deviceAddress} txt=${it.txt}")
            }
        }
        appendLine(
            if (sawAirDropOverMdns) {
                "mDNS on wlan0: an _airdrop._tcp record IS visible on the infrastructure network."
            } else {
                "mDNS on wlan0: no _airdrop._tcp record (${mdns.size} other Apple record(s))."
            }
        )
    }
}

/**
 * Runs every transport an Android app can observe, at once, and reports what each one saw.
 *
 * ## Purpose
 *
 * To answer one question with data instead of recollection: **when an iPhone is actively
 * soliciting an AirDrop transfer, is it reachable from an ordinary Android app over any
 * transport the platform exposes?**
 *
 * The three legs are run simultaneously and on purpose:
 *  - [AppleBeaconScanner] proves the iPhone is in AirDrop discovery *at that moment*. Without
 *    it, a negative result elsewhere means nothing — it could just be that the sheet timed out.
 *  - [WifiDirectProbe] tests peer and DNS-SD visibility over Wi-Fi Direct.
 *  - [MdnsBrowser] tests whether `_airdrop._tcp` is reachable on the infrastructure network.
 *
 * ## What a negative result does and does not prove
 *
 * If the beacon is present and both other legs stay empty, AirDrop's discovery and transfer
 * are not reachable from this app, and no amount of Kotlin will make them reachable. That is a
 * statement about *this device and this API surface*, which is exactly the scope that matters
 * for shipping something here. It is **not** a claim about Apple's internals, and the probe is
 * built so that a genuine failure to probe ([P2pObservations.unavailableReason]) is reported
 * distinctly from a successful probe that found nothing.
 *
 * Findings from real runs belong in `share/AIRDROP_FINDINGS.md`, alongside the logcat filter
 * used to capture them.
 */
class AirDropProbe(context: Context) {

    private val beacons = AppleBeaconScanner(context)
    private val wifiDirect = WifiDirectProbe(context)
    private val mdns = MdnsBrowser(context)

    /**
     * Start every leg and emit a fresh report whenever any of them changes.
     *
     * Cancelling the collection tears down the BLE scan, the P2P discovery and every mDNS
     * browse — all three hold radio resources and none of them stop on their own.
     */
    fun run(): Flow<AirDropProbeReport> = channelFlow {
        mdns.reset()
        launch { beacons.scan().collect { } }
        launch { wifiDirect.probe().collect { } }
        APPLE_SERVICE_TYPES.forEach { type ->
            launch { mdns.browse(type).collect { } }
        }
        // Rotating private addresses mean the beacon map would otherwise grow without bound
        // over a long capture, and stale entries would overstate how many phones are present.
        launch {
            while (true) {
                delay(BEACON_EXPIRY_MS)
                beacons.expire(BEACON_EXPIRY_MS)
            }
        }
        launch {
            combine(
                beacons.sightings,
                wifiDirect.observations,
                mdns.found,
            ) { sightings, p2p, records ->
                AirDropProbeReport(
                    appleBeacons = sightings.values.sortedByDescending { it.rssi },
                    p2p = p2p,
                    mdns = records.values.toList(),
                )
            }.collect { send(it) }
        }
    }

    private companion object {
        /**
         * How long a beacon stays in the report after its last sighting.
         *
         * An iPhone re-advertises several times a second while its sheet is open, so anything
         * this stale is gone. Short enough that closing the sheet is visible in the UI within
         * a few seconds.
         */
        const val BEACON_EXPIRY_MS = 10_000L
    }
}
