package com.vayunmathur.cast.platform.remotedisplay

import android.content.Context
import android.util.Log
import com.android.media.remotedisplay.RemoteDisplay
import com.android.media.remotedisplay.RemoteDisplayProvider
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.domain.ClientState
import com.vayunmathur.cast.platform.CastController
import com.vayunmathur.cast.platform.CastPairActivity
import com.vayunmathur.cast.platform.MirrorPhase
import com.vayunmathur.library.ui.ExternalIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "CastRemoteDisplay"

/**
 * How long a pair prompt may go unanswered before the route is given up.
 *
 * Long enough to read six digits off a television and type them, and short enough that a prompt the
 * user walked away from does not leave the row spinning indefinitely - which is the behaviour this
 * whole path exists to remove.
 */
private const val PAIR_PROMPT_TIMEOUT_MS = 120_000L

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

    /** The route a pair prompt is currently up for, so it is launched once rather than per emission. */
    private var pairPromptId: String? = null

    /**
     * The route that already has a desktop display.
     *
     * The phases meaning "paired" re-emit, and starting desktop mode twice would tear down the
     * first display to build a second - so this is what makes it once-per-route rather than
     * once-per-emission.
     */
    private var desktopStartedId: String? = null

    /** Gives up on [pairPromptId] if the digits never arrive. */
    private var pairTimeoutJob: Job? = null

    init {
        scope.launch { CastController.mirrorPhase.collect { onMirrorPhase(it) } }
        scope.launch { CastController.sessionState.collect { onSessionState(it) } }
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
        // Pairing may stop here and wait for six digits off the TV. A route row has no text field, so
        // [onSessionState] puts `CastPairActivity` over Settings to take them; `thenMirror` is what
        // carries on once the TV has accepted.
        //
        // `thenMirror = false` because a route selected in SETTINGS does not go down the
        // MediaProjection path at all. Screen mirroring would ask for capture consent and send a
        // copy of the phone; instead [onMirrorReady] starts desktop mode, which creates a
        // system-owned display the window manager can place activities on. That needs no consent
        // dialog, and it is what lets Settings offer mirror-or-extend for the route.
        CastController.connect(appContext, device, thenMirror = false)
    }

    override fun onDisconnect(display: RemoteDisplay) {
        connectingId = null
        desktopStartedId = null
        clearPairPrompt()
        CastController.disconnect(appContext)
        display.status = RemoteDisplay.STATUS_AVAILABLE
        // The display went with the session, so the route must stop advertising one - a stale id
        // names a display that no longer exists, which is exactly what
        // MediaRouterService.computePresentationDisplayId cannot detect.
        display.presentationDisplayId = -1
        updateDisplay(display)
    }

    fun shutdown() {
        pairTimeoutJob?.cancel()
        scope.cancel()
    }

    /**
     * Drive the pair prompt for a route selected in Settings.
     *
     * Only ever for [connectingId] - a session the app itself started has its own screen showing the
     * pair-code card, and putting a popup over it would be asking twice.
     */
    private fun onSessionState(state: ClientState) {
        val id = connectingId ?: return
        when (state.phase) {
            ClientPhase.AwaitingCode -> promptForCode(id)
            // Answered. The row's status follows mirroring from here, via [onMirrorPhase].
            ClientPhase.Connecting, ClientPhase.Paired, ClientPhase.Streaming -> {
                clearPairPrompt()
                startDesktopIfNeeded(id, state.phase)
            }
            // Dismissed, refused, or the TV went away mid-pairing. `mirrorPhase` never moved off
            // Idle for any of these, so [onMirrorPhase] will not emit and nothing else would take
            // this row off CONNECTING.
            ClientPhase.Idle, ClientPhase.Failed -> if (pairPromptId != null) giveUpRoute(id)
        }
    }

    /**
     * Start desktop mode once the TV has accepted us, and publish the display back to the route.
     *
     * Only for [connectingId] - a session the app started itself is screen mirroring and owns its
     * own MediaProjection. Guarded by [desktopStartedId] because the phases that mean "paired"
     * re-emit, and a second `startDesktopMode` would tear down the first display to build another.
     *
     * The id matters as much as the display: `MediaRouterService.computePresentationDisplayId`
     * only ever READS `presentationDisplayId` off the descriptor we publish - it never creates a
     * display, and its own comment requires the id to already name one that exists. Without this
     * call the display would be live and the framework would not know it belonged to the route.
     */
    private fun startDesktopIfNeeded(id: String, phase: ClientPhase) {
        if (phase == ClientPhase.Connecting) return // Paired, but no stream agreed yet.
        if (desktopStartedId == id) return
        desktopStartedId = id
        CastController.startDesktopMode(appContext) { displayId ->
            val route = displays.firstOrNull { it.id == id } ?: return@startDesktopMode
            route.presentationDisplayId = displayId
            updateDisplay(route)
            Log.i(TAG, "published system display $displayId for route $id")
        }
    }

    private fun promptForCode(id: String) {
        if (pairPromptId == id) return // Already asked; a wrong code re-emits the same phase.
        pairPromptId = id
        ExternalIntents.launch(appContext, CastPairActivity.intent(appContext))
        pairTimeoutJob?.cancel()
        pairTimeoutJob = scope.launch {
            delay(PAIR_PROMPT_TIMEOUT_MS)
            Log.i(TAG, "no pair code arrived for $id; giving the route up")
            // Dropped before [giveUpRoute], which clears the prompt - and clearing the prompt
            // cancels this job, which is the one currently running.
            pairTimeoutJob = null
            giveUpRoute(id)
            CastController.disconnect(appContext)
        }
    }

    /**
     * Stop offering a route whose pairing was never completed.
     *
     * `STATUS_NOT_AVAILABLE` rather than `STATUS_AVAILABLE` so the row reads as "this did not work"
     * instead of inviting the same dead-end again. It is not permanent: leaving the Cast page sets
     * `DISCOVERY_MODE_NONE`, which clears every route, so coming back rebuilds this one as available.
     */
    private fun giveUpRoute(id: String) {
        clearPairPrompt()
        connectingId = null
        val display = routes[id] ?: return
        display.status = RemoteDisplay.STATUS_NOT_AVAILABLE
        updateDisplay(display)
    }

    private fun clearPairPrompt() {
        pairPromptId = null
        pairTimeoutJob?.cancel()
        pairTimeoutJob = null
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
