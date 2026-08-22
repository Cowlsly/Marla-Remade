package com.vayunmathur.share.platform.discovery.apple

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

private const val TAG = "WifiDirectProbe"

/** A Wi-Fi Direct peer, flattened to the fields worth logging. */
data class P2pPeer(
    val deviceName: String,
    val deviceAddress: String,
    val primaryDeviceType: String?,
    val secondaryDeviceType: String?,
    val status: Int,
    val isGroupOwner: Boolean,
    val seenAtMs: Long,
) {
    val statusName: String
        get() = when (status) {
            WifiP2pDevice.CONNECTED -> "CONNECTED"
            WifiP2pDevice.INVITED -> "INVITED"
            WifiP2pDevice.FAILED -> "FAILED"
            WifiP2pDevice.AVAILABLE -> "AVAILABLE"
            WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
            else -> "UNKNOWN($status)"
        }
}

/** A DNS-SD service record advertised over Wi-Fi Direct by a peer. */
data class P2pService(
    val instanceName: String,
    val registrationType: String,
    val deviceAddress: String,
    val deviceName: String,
    val txt: Map<String, String> = emptyMap(),
)

/** Everything the probe has observed so far. */
data class P2pObservations(
    val peers: Map<String, P2pPeer> = emptyMap(),
    val services: Map<String, P2pService> = emptyMap(),
    /** Set when the probe could not run at all, with the reason. */
    val unavailableReason: String? = null,
)

/**
 * Wi-Fi Direct (Wi-Fi P2P) peer and DNS-SD service discovery.
 *
 * ## Why this exists
 *
 * To determine, empirically and on real hardware, whether an iPhone soliciting an AirDrop
 * transfer is reachable over Wi-Fi Direct. It is deliberately a *probe* and not a transport:
 * it discovers and logs, and connects to nothing.
 *
 * Run it alongside [AppleBeaconScanner] with an iPhone's share sheet open. The BLE scanner
 * establishes the ground truth that the phone is beaconing AirDrop *right now*; this probe
 * establishes whether that same phone is simultaneously visible as a Wi-Fi Direct peer, and
 * whether it publishes any DNS-SD service (an `_airdrop._tcp` record in particular) over that
 * link. Those two observations together are what settle the transport question — see
 * `AIRDROP_FINDINGS.md` for how to run it and what was actually observed.
 *
 * A DNS-SD request is issued for *all* service types rather than for `_airdrop._tcp`
 * specifically, because a negative result on a narrow filter is uninformative: it cannot
 * distinguish "the peer publishes nothing" from "the peer publishes something under a name we
 * did not ask for".
 *
 * ## Permissions
 *
 * On API 33+ [Manifest.permission.NEARBY_WIFI_DEVICES] covers both peer and service
 * discovery. On API 31–32 the platform requires [Manifest.permission.ACCESS_FINE_LOCATION]
 * instead, which `:share` deliberately does not request, so the probe reports itself
 * unavailable there rather than adding a location permission for a diagnostic.
 */
class WifiDirectProbe(private val context: Context) {

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private val _observations = MutableStateFlow(P2pObservations())
    val observations: StateFlow<P2pObservations> = _observations.asStateFlow()

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Why the probe cannot run, or null if it can.
     *
     * Reported rather than thrown: "Wi-Fi Direct is unsupported here" is itself a finding, and
     * a probe that silently produces nothing is indistinguishable from a probe that ran and
     * found nothing — which is exactly the distinction this whole exercise turns on.
     */
    private fun unavailableReason(): String? = when {
        manager == null -> "no WifiP2pManager (Wi-Fi Direct unsupported on this device)"
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) ->
            "device does not declare FEATURE_WIFI_DIRECT"
        Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ->
            "missing NEARBY_WIFI_DEVICES"
        Build.VERSION.SDK_INT < 33 ->
            "API ${Build.VERSION.SDK_INT} requires ACCESS_FINE_LOCATION for P2P discovery, " +
                "which :share does not request"
        else -> null
    }

    @SuppressLint("MissingPermission")
    fun probe(): Flow<P2pObservations> = callbackFlow {
        // Start from nothing on every run: observations from a previous run say nothing about
        // what is in range now, and this probe is read as a point-in-time measurement.
        _observations.value = P2pObservations()
        val reason = unavailableReason()
        if (reason != null || manager == null) {
            val why = reason ?: "no WifiP2pManager"
            Log.w(TAG, "Wi-Fi Direct probe unavailable: $why")
            _observations.value = _observations.value.copy(unavailableReason = why)
            trySend(_observations.value)
            close()
            return@callbackFlow
        }

        val channel = manager.initialize(context, Looper.getMainLooper(), null) ?: run {
            Log.w(TAG, "WifiP2pManager.initialize returned null")
            _observations.value = _observations.value.copy(unavailableReason = "initialize failed")
            trySend(_observations.value)
            close()
            return@callbackFlow
        }

        fun publish() {
            trySend(_observations.value)
        }

        // ----- peers -----------------------------------------------------
        val peerListener = WifiP2pManager.PeerListListener { list: WifiP2pDeviceList? ->
            val now = System.currentTimeMillis()
            val peers = list?.deviceList.orEmpty().associate { d ->
                d.deviceAddress to P2pPeer(
                    deviceName = d.deviceName ?: "",
                    deviceAddress = d.deviceAddress ?: "",
                    primaryDeviceType = d.primaryDeviceType,
                    secondaryDeviceType = d.secondaryDeviceType,
                    status = d.status,
                    isGroupOwner = d.isGroupOwner,
                    seenAtMs = now,
                )
            }
            // Replace, do not merge. requestPeers reports the complete current list, so a peer
            // that has gone away is absent from it — and retaining it would turn "no peers while
            // AirDrop is beaconing" into a false sighting, which is the one mistake this probe
            // must not make.
            val previous = _observations.value.peers
            _observations.value = _observations.value.copy(peers = peers)
            peers.values.filter { previous[it.deviceAddress]?.status != it.status }.forEach { p ->
                Log.i(
                    TAG,
                    "P2P peer \"${p.deviceName}\" ${p.deviceAddress} ${p.statusName} " +
                        "primaryType=${p.primaryDeviceType} groupOwner=${p.isGroupOwner}",
                )
            }
            (previous.keys - peers.keys).forEach { Log.i(TAG, "P2P peer $it gone") }
            publish()
        }

        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                        try {
                            manager.requestPeers(channel, peerListener)
                        } catch (e: SecurityException) {
                            Log.w(TAG, "requestPeers denied", e)
                        }

                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        Log.i(TAG, "Wi-Fi P2P ${if (enabled) "enabled" else "disabled"}")
                        if (!enabled) {
                            _observations.value = _observations.value
                                .copy(unavailableReason = "Wi-Fi Direct is turned off")
                            publish()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // ----- DNS-SD services -------------------------------------------
        val serviceListener = WifiP2pManager.DnsSdServiceResponseListener {
                instanceName, registrationType, srcDevice ->
            val key = "$instanceName|$registrationType|${srcDevice?.deviceAddress}"
            val svc = P2pService(
                instanceName = instanceName ?: "",
                registrationType = registrationType ?: "",
                deviceAddress = srcDevice?.deviceAddress ?: "",
                deviceName = srcDevice?.deviceName ?: "",
            )
            val existing = _observations.value.services[key]
            _observations.value = _observations.value.copy(
                services = _observations.value.services + (key to (existing ?: svc)),
            )
            Log.i(
                TAG,
                "P2P DNS-SD service \"$instanceName\" type=$registrationType " +
                    "from ${srcDevice?.deviceName} ${srcDevice?.deviceAddress}",
            )
            publish()
        }
        val txtListener = WifiP2pManager.DnsSdTxtRecordListener {
                fullDomainName, txtRecordMap, srcDevice ->
            Log.i(
                TAG,
                "P2P DNS-SD TXT $fullDomainName from ${srcDevice?.deviceAddress} = $txtRecordMap",
            )
            // Attach the TXT map to whichever service record it belongs to. The domain name is
            // the only correlator the two callbacks share, and they arrive in either order.
            val instance = fullDomainName?.substringBefore('.') ?: return@DnsSdTxtRecordListener
            _observations.value = _observations.value.copy(
                services = _observations.value.services.mapValues { (_, svc) ->
                    if (svc.instanceName == instance && svc.deviceAddress == srcDevice?.deviceAddress) {
                        svc.copy(txt = txtRecordMap.orEmpty())
                    } else {
                        svc
                    }
                },
            )
            publish()
        }
        manager.setDnsSdResponseListeners(channel, serviceListener, txtListener)

        fun actionListener(what: String) = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "$what: started")
            }

            override fun onFailure(reasonCode: Int) {
                val name = when (reasonCode) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                    WifiP2pManager.ERROR -> "ERROR"
                    WifiP2pManager.BUSY -> "BUSY"
                    WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
                    else -> "reason=$reasonCode"
                }
                Log.w(TAG, "$what: failed ($name)")
            }
        }

        // newInstance() with no arguments asks for every service type; see the class KDoc for
        // why a narrow _airdrop._tcp filter would not be a usable negative result.
        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()

        fun startDiscovery() {
            try {
                manager.discoverServices(channel, actionListener("discoverServices"))
                manager.discoverPeers(channel, actionListener("discoverPeers"))
            } catch (e: SecurityException) {
                Log.w(TAG, "P2P discovery denied", e)
                _observations.value =
                    _observations.value.copy(unavailableReason = "denied: ${e.message}")
                publish()
            }
        }

        try {
            manager.addServiceRequest(channel, serviceRequest, actionListener("addServiceRequest"))
        } catch (e: SecurityException) {
            Log.w(TAG, "addServiceRequest denied", e)
            _observations.value = _observations.value.copy(unavailableReason = "denied: ${e.message}")
            publish()
        }

        // Re-issue discovery on a timer instead of once. A single discoverPeers() covers one scan
        // cycle and then lapses, so a one-shot call can miss a peer that appeared seconds later —
        // and this probe's whole value rests on "no peer while AirDrop was beaconing" being a
        // real absence rather than a scan that had already finished.
        launch {
            while (true) {
                startDiscovery()
                delay(REDISCOVER_INTERVAL_MS)
            }
        }

        publish()

        awaitClose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
            @SuppressLint("MissingPermission")
            fun cleanup() {
                try {
                    manager.removeServiceRequest(channel, serviceRequest, actionListener("removeServiceRequest"))
                    manager.stopPeerDiscovery(channel, actionListener("stopPeerDiscovery"))
                } catch (_: Exception) {
                }
            }
            cleanup()
            // close() releases the framework-side channel; without it the next probe on the
            // same process silently gets BUSY.
            try {
                channel.close()
            } catch (_: Exception) {
            }
            Log.d(TAG, "Wi-Fi Direct probe stopped")
        }
    }

    private companion object {
        /**
         * How often peer and service discovery are re-issued.
         *
         * Wi-Fi P2P discovery cycles the social channels and then lapses; 10s keeps a scan
         * effectively always in flight without thrashing the radio.
         */
        const val REDISCOVER_INTERVAL_MS = 10_000L
    }
}
