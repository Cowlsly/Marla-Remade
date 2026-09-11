package com.vayunmathur.library.map

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.util.ConnectivityMonitor

/**
 * Hosts the Vulkan renderer in Compose.
 *
 * The renderer itself is [SurfaceMapRenderer], which is Compose-free and public so a
 * non-Compose host — Android Auto's `SurfaceContainer`, for one — can drive the same code.
 * This file is only the TextureView adapter and the effects that push Compose state into it.
 *
 * ## TextureView, not SurfaceView
 *
 * `maps/src/main/java/com/vayunmathur/maps/ui/map/MapSurface.kt:76-83` records why, from
 * production: a **SurfaceView renders into a separate surface and goes black and stops
 * taking input** after the composable is disposed navigating forward and recomposed on
 * the back-pop through Nav3's `AnimatedContent` transition — it only repaints once some
 * later recomposition forces a relayout. A TextureView draws in the normal view
 * hierarchy, so it composites and stays interactive across the transition.
 *
 * `games/voxels/.../ui/VoxelSurfaceView.kt` is a SurfaceView, but voxels' surface is
 * never navigated away from and back. Seven consumer apps' navigation is, so the
 * known-good configuration wins over the cheaper one. The cost is one extra composite
 * per frame.
 *
 * A `SurfaceTexture` also gives the lifecycle honestly: returning true from
 * [TextureView.SurfaceTextureListener.onSurfaceTextureDestroyed] means we release it, so
 * the renderer is torn down — device idle, buffers freed, `ANativeWindow` released —
 * exactly when the window goes away.
 */
@Composable
internal fun VulkanMapSurface(
    cameraState: CameraState,
    darkBasemap: Boolean,
    muted: Boolean,
    layerOptions: LayerOptions = LayerOptions(),
    archivePath: String? = null,
    userPuck: UserPuck? = null,
    /** Dim everything outside the administrative region this names. `null` draws no mask. */
    regionMask: RegionMask? = null,
    /**
     * The live-traffic colour table. `null` or an empty table clears the overlay; otherwise its
     * `component_id → ARGB` entries are pushed to the renderer. Has no visible effect unless
     * [LayerOptions.traffic] is on.
     */
    trafficColors: TrafficColorTable? = null,
    /**
     * The navigation route to draw inside the renderer's frame. `null` or an all-empty
     * overlay draws nothing. Pushed like [regionMask], out of band from the frame loop, so
     * it pans in lock-step with the basemap.
     */
    route: RouteOverlay? = null,
    /** App pins the renderer draws as billboarded sprites; empty draws none. */
    markers: List<MapMarker> = emptyList(),
    /**
     * Simulated transit vehicles the renderer draws as billboarded sprites, on the same shared
     * sprite path as [markers] but pushed on their own ~1 Hz cadence; empty draws none. Kept apart
     * from [markers] so a vehicle recompute does not churn the pins and the moving sprites stay out
     * of the pin tap-pick.
     */
    vehicles: List<MapMarker> = emptyList(),
    modifier: Modifier = Modifier,
    onFrame: () -> Unit = {},
    fallback: @Composable (MapRenderState.Unavailable) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val host = remember(context, archivePath) { MapSurfaceHost(context, cameraState, density, archivePath, onFrame) }
    val renderer = host.renderer

    // Task-17 pick wiring: the projection answers queryRenderedLabels from
    // the native placed-label snapshot owned by this surface's renderer.
    // Registered here (not in the host) so disposal clears it: a projection
    // without a live renderer answers empty rather than hitting a dead handle.
    DisposableEffect(host, cameraState) {
        cameraState.labelQueryProvider = { box: DpRect, layerIds: Set<String> ->
            renderer.pickLabels(box, layerIds)
        }
        // The marker pick counterpart: taps resolve to a renderer-drawn pin's id through the id
        // buffer. Cleared on dispose so a projection without a live renderer answers 0, not a dead
        // handle.
        cameraState.markerPickProvider = { xDp: Float, yDp: Float -> renderer.pickAt(xDp, yDp) }
        onDispose {
            cameraState.labelQueryProvider = null
            cameraState.markerPickProvider = null
        }
    }

    DisposableEffect(host) { onDispose { host.dispose() } }

    // Stop driving Vulkan while the host is not visible. ON_START/ON_STOP rather than
    // resume/pause because a STARTED-but-not-RESUMED app — split-screen, PiP — is on screen
    // and must keep drawing.
    //
    // This stops *rendering* only. The Rust side's in-flight tile fetches are not paused,
    // and that is where the data cost actually is, so backgrounding does not fully quiesce
    // the map.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, host) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> renderer.start()
                Lifecycle.Event.ON_STOP -> renderer.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            renderer.stop()
        }
    }

    // The renderer draws on demand rather than every vsync, and it *pulls* its camera out of
    // the CameraState inside its frame callback — so nothing would otherwise tell it that a
    // gesture, a fling, `animateTo` or a layout pass has moved the camera while the loop is
    // idle, and the map would sit still under the user's finger.
    //
    // Deliberately a snapshotFlow over the state rather than a call at each mutation site:
    // pan, pinch, tilt, quick-zoom, double-tap zoom, `animateTo` and `setViewport` all write
    // these two properties and nothing else, so observing the properties covers every one of
    // them — including any added later, which naming the call sites would not.
    //
    // The cost is that the first frame of an interaction can be one frame late, where the pull
    // was immediate. The renderer's idle grace then holds the loop open for the rest of the
    // gesture, so it is one frame at the start of a movement and never during it.
    LaunchedEffect(host, cameraState) {
        snapshotFlow { cameraState.position }.collect { renderer.invalidate() }
    }
    // Separately, because a viewport change is rare and a camera change is per-frame: keeping
    // them apart means the hot path compares one reference rather than allocating a pair.
    LaunchedEffect(host, cameraState) {
        snapshotFlow { cameraState.viewportDp }.collect { renderer.invalidate() }
    }

    // Following the system theme costs nothing: the layer set is identical between
    // variants, so this re-colours in place rather than reloading a single tile.
    LaunchedEffect(darkBasemap, muted, host) { renderer.setPalette(darkBasemap, muted) }

    // Unlike the palette, this one is not free: the optional layers are gated at
    // tessellation time, so a change re-tessellates the resident set. `setLayers` ignores
    // a call that changes nothing, which is what makes driving it from an effect safe.
    LaunchedEffect(layerOptions, host) { renderer.setLayers(layerOptions) }

    // A setter rather than an argument on the frame loop: a fix arrives at about 1 Hz and
    // `render` runs at 60, so the puck is state the renderer holds between frames.
    LaunchedEffect(userPuck, host) { renderer.setUserPuck(userPuck) }

    // Same reasoning as the puck: a selection arrives from a tap, not from the frame loop.
    LaunchedEffect(regionMask, host) { renderer.setRegionMask(regionMask) }

    // The traffic colour table: pushed like the region mask, out of band from the frame loop.
    // Keyed on the table's *value* (content equality) so a host rebuilding an identical table
    // on recomposition does not re-push. `null` or empty clears — a pure state swap either way,
    // no tessellation. The layer-10 on/off gate rides `layerOptions` above, not this.
    LaunchedEffect(trafficColors, host) {
        val table = trafficColors
        if (table == null || table.isEmpty()) {
            renderer.clearTraffic()
        } else {
            renderer.setTrafficSpeeds(table.ids, table.argb)
        }
    }

    // The route: pushed like the region mask, out of band from the frame loop. Keyed on the
    // overlay's *value* (data-class equality) so the phone rebuilding an identical route on
    // recomposition does not re-tessellate. `null` or an all-empty overlay clears it.
    LaunchedEffect(route, host) { renderer.setRoute(route) }

    // App pins: pushed like the puck, out of band from the frame loop, so they pan and tilt in
    // lock-step with the basemap. Keyed on the list's value so an identical set rebuilt on
    // recomposition does not re-push; an empty list clears them.
    LaunchedEffect(markers, host) { renderer.setMarkers(markers) }

    // Simulated transit vehicles: pushed like the pins but on their own ~1 Hz cadence, keyed on the
    // list's value so an identical recompute does not re-push. An empty list clears them, which is
    // how the ticker stops them when the transit toggle goes off or the surface is hidden.
    LaunchedEffect(vehicles, host) { renderer.setVehicles(vehicles) }

    // Live connectivity, replacing a single sample taken in onSurfaceTextureAvailable.
    // Collected here rather than inside the renderer so every MapNative call stays on the
    // composition dispatcher, like setPalette and setLayers.
    //
    // Two deliberate behaviour changes from the probe this replaces. ConnectivityMonitor
    // requires NET_CAPABILITY_VALIDATED, which the probe did not — an improvement for a tile
    // fetcher, since a captive portal handing back HTML for a byte range is worse than
    // knowing you are offline. And it reports false where there is no ConnectivityManager,
    // where the probe optimistically returned true; that only arises under unit/Robolectric,
    // where the effect is to pin the map to cache.
    LaunchedEffect(host) {
        // Seeded synchronously so the first value is not spuriously false. This also
        // lazily starts the monitor.
        renderer.setOnline(ConnectivityMonitor.isOnline(context))
        ConnectivityMonitor.isOnline.collect { renderer.setOnline(it) }
    }

    AndroidView(
        factory = {
            TextureView(context).apply {
                // The map is opaque, so tell the compositor: a translucent TextureView is
                // blended every frame for nothing.
                isOpaque = true
                surfaceTextureListener = host
            }
        },
        modifier = modifier,
    )

    // Over the TextureView rather than instead of it: the listener that reports the failure
    // only runs once the view is attached, so removing the view would remove the thing that
    // produces the state.
    (renderer.renderState as? MapRenderState.Unavailable)?.let { fallback(it) }
}

/**
 * Binds one [SurfaceMapRenderer] to one [TextureView]'s `SurfaceTexture`.
 *
 * All it owns beyond the renderer is the [Surface] wrapping the texture, because
 * [SurfaceMapRenderer] deliberately does not release a surface it did not create. Every
 * callback here is on the main thread, which is what the renderer requires.
 */
private class MapSurfaceHost(
    context: Context,
    cameraState: CameraState,
    density: Float,
    archivePath: String?,
    onFrame: () -> Unit,
) : TextureView.SurfaceTextureListener {

    val renderer = SurfaceMapRenderer(context, density, archivePath, onFrame).apply {
        composeCamera = cameraState
    }

    private var surface: Surface? = null

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val created = Surface(texture)
        surface = created
        renderer.attachSurface(created, width, height)
        // Nothing holds the window on a failed start, so give the Surface back rather than
        // leaving it pinned to a texture we will never draw into.
        if (renderer.renderState is MapRenderState.Unavailable) releaseSurface()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        renderer.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        // Detach before releasing the Surface: the native side waits for the GPU to go idle
        // and releases the ANativeWindow that points at it.
        renderer.detachSurface()
        releaseSurface()
        // True: we release the SurfaceTexture, having already dropped the
        // ANativeWindow that pointed at it.
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun dispose() {
        renderer.destroy()
        releaseSurface()
    }

    private fun releaseSurface() {
        surface?.release()
        surface = null
    }
}
