package com.vayunmathur.cast.platform.remotedisplay

import android.content.Context
import android.util.Log
import com.android.media.remotedisplay.RemoteDisplay
import com.android.media.remotedisplay.RemoteDisplayProvider
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.platform.CastController
import com.vayunmathur.cast.platform.MirrorPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "CastRemoteDisplay"

/**
 * Publishes discovered MA Cast receivers to the framework as remote display routes.
 *
 * This is what puts the app in Settings' Cast page. That screen is `WifiDisplaySettings`, which
 * lists `MediaRouter` routes of type `ROUTE_TYPE_REMOTE_DISPLAY`; those routes come from provider
 * services that `RemoteDisplayProviderWatcher` finds, binds and drives through this class. The
 * watcher only trusts a service guarded by `BIND_REMOTE_DISPLAY` in an app holding
 * `REMOTE_DISPLAY_PROVIDER`, so both are required for any of this to appear - see the manifest.
 *
 * **No second discovery path.** The routes are the same [CastDevice]s the in-app list shows, from
 * the same [com.vayunmathur.cast.platform.discovery.CastDiscoveryManager] owned by [CastController],
 * and connecting drives the same session. A route selected here and a device tapped in the app are
 * the same action arriving from two places, which is also why route status is derived from
 * [CastController]'s own phase rather than tracked separately - the app's Stop button and the
 * route's disconnect have to agree.
 *
 * Everything runs on the main thread: the framework calls the callbacks there, and
 * `RemoteDisplayProvider`'s own published state is not synchronised.
 */
class MaRemoteDisplayProvider(context: Context) : RemoteDisplayProvider(context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Route by [CastDevice.id], which is also the route id handed back on connect. */
    private val routes = mutableMapOf<String, RemoteDisplay>()
    private val devices = mutableMapOf<String, CastDevice>()

    /** The browse plus the two observers, all cancelled together when discovery is turned off. */
    private var discoveryJob: Job? = null

    /** The route the user selected, so a phase change knows which row to move. */
    private var connectingId: String? = null

    init {
        scope.launch { CastController.mirrorPhase.collect { onMirrorPhase(it) } }
    }

    override fun onDiscoveryModeChanged(mode: Int) {
        if (mode == RemoteDisplayProvider.DISCOVERY_MODE_NONE) {
            discoveryJob?.cancel()
            discoveryJob = null
            clearRoutes()
            return
        }
        if (discoveryJob != null) return
        val discovery = CastController.discovery(appContext)
        discoveryJob = scope.launch {
            // The browse itself. `devices` only fills while something is collecting `discover()`,
            // so this collection is what makes the list happen rather than a duplicate of it - the
            // devices are read off the state flow below, not out of this lambda.
            launch { discovery.discover().collect { } }
            launch { discovery.devices.collect { syncRoutes(it) } }
        }
    }

    override fun onConnect(display: RemoteDisplay) {
        val id = display.id ?: return
        val device = devices[id]
        if (device == null) {
            Log.w(TAG, "asked to connect to a route with no device behind it: $id")
            display.status = RemoteDisplay.STATUS_NOT_AVAILABLE
            updateDisplay(display)
            return
        }
        connectingId = id
        display.status = RemoteDisplay.STATUS_CONNECTING
        updateDisplay(display)
        // Pairing may stop here and wait for six digits off the TV, which only the app's own UI can
        // take - there is no text field in a route row. `thenMirror` is what carries on to consent
        // once the TV has accepted.
        CastController.connect(appContext, device, thenMirror = true)
    }

    override fun onDisconnect(display: RemoteDisplay) {
        connectingId = null
        CastController.disconnect(appContext)
        display.status = RemoteDisplay.STATUS_AVAILABLE
        updateDisplay(display)
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun onMirrorPhase(phase: MirrorPhase) {
        val id = connectingId ?: return
        val display = routes[id] ?: return
        val status = when (phase) {
            MirrorPhase.Mirroring -> RemoteDisplay.STATUS_CONNECTED
            MirrorPhase.Negotiating -> RemoteDisplay.STATUS_CONNECTING
            // Idle covers both "never started" and "the user stopped", and Failed has already put a
            // sentence in front of the user in the app; either way this route is selectable again.
            MirrorPhase.Idle, MirrorPhase.Failed -> RemoteDisplay.STATUS_AVAILABLE
        }
        if (status == RemoteDisplay.STATUS_AVAILABLE) connectingId = null
        if (display.status == status) return
        display.status = status
        updateDisplay(display)
    }

    private fun syncRoutes(found: List<CastDevice>) {
        val seen = found.associateBy { it.id }
        devices.clear()
        devices.putAll(seen)
        for ((id, device) in seen) {
            val existing = routes[id]
            if (existing == null) {
                routes[id] = RemoteDisplay(id, device.friendlyName).apply {
                    description = appContext.getString(R.string.cast_route_description)
                    status = RemoteDisplay.STATUS_AVAILABLE
                }.also { addDisplay(it) }
                continue
            }
            if (existing.name != device.friendlyName) {
                existing.name = device.friendlyName
                updateDisplay(existing)
            }
        }
        // A receiver that stopped announcing itself is gone, unless it is the one being cast to -
        // mDNS goes quiet on a busy network and dropping the live route would leave the session
        // running with nothing in Settings to stop it from.
        val stale = routes.keys.filter { it !in seen && it != connectingId }
        for (id in stale) routes.remove(id)?.let { removeDisplay(it) }
    }

    private fun clearRoutes() {
        for (display in routes.values) removeDisplay(display)
        routes.clear()
        devices.clear()
    }
}
