package com.vayunmathur.library.map

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.map.PlacedLabel
import com.vayunmathur.library.util.ConnectivityMonitor
import java.io.File

/**
 * Hosts the Vulkan renderer in Compose.
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
    modifier: Modifier = Modifier,
    onFrame: () -> Unit = {},
    fallback: @Composable (MapRenderState.Unavailable) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val host = remember(context, archivePath) { MapSurfaceHost(context, cameraState, density, archivePath, onFrame) }

    // Task-17 pick wiring: the projection answers queryRenderedLabels from
    // the native placed-label snapshot owned by this surface's renderer.
    // Registered here (not in the host) so disposal clears it: a projection
    // without a live renderer answers empty rather than hitting a dead handle.
    DisposableEffect(host, cameraState) {
        cameraState.labelQueryProvider = { box: DpRect, layerIds: Set<String> ->
            host.pickLabels(box, layerIds)
        }
        onDispose { cameraState.labelQueryProvider = null }
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
                Lifecycle.Event.ON_START -> host.onStarted()
                Lifecycle.Event.ON_STOP -> host.onStopped()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            host.onStopped()
        }
    }

    // Following the system theme costs nothing: the layer set is identical between
    // variants, so this re-colours in place rather than reloading a single tile.
    LaunchedEffect(darkBasemap, muted, host) { host.setPalette(darkBasemap, muted) }

    // Unlike the palette, this one is not free: the optional layers are gated at
    // tessellation time, so a change re-tessellates the resident set. `setLayers` ignores
    // a call that changes nothing, which is what makes driving it from an effect safe.
    LaunchedEffect(layerOptions, host) { host.setLayers(layerOptions) }

    // A setter rather than an argument on the frame loop: a fix arrives at about 1 Hz and
    // `render` runs at 60, so the puck is state the renderer holds between frames.
    LaunchedEffect(userPuck, host) { host.setUserPuck(userPuck) }

    // Live connectivity, replacing a single sample taken in onSurfaceTextureAvailable.
    // Collected here rather than inside the host so every MapNative call stays on the
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
        host.setOnline(ConnectivityMonitor.isOnline(context))
        ConnectivityMonitor.isOnline.collect { host.setOnline(it) }
    }

    AndroidView(
        factory = {
            TextureView(context).apply {
                // The map is opaque, so tell the compositor: a translucent TextureView is
                // blended every frame for nothing.
                isOpaque = true
                surfaceTextureListener = host.listener
            }
        },
        modifier = modifier,
    )

    // Over the TextureView rather than instead of it: the listener that reports the failure
    // only runs once the view is attached, so removing the view would remove the thing that
    // produces the state.
    (host.renderState as? MapRenderState.Unavailable)?.let { fallback(it) }
}

/**
 * Owns the native renderer and the frame loop for one surface.
 *
 * Every native call happens on the main thread — the `Choreographer` callback is on it
 * anyway, and the renderer is not safe to drive from two threads at once. The expensive
 * work (range fetch, inflate, MVT decode, tessellation) is on a worker thread inside the
 * native side, so this callback only uploads finished meshes and records a command buffer.
 */
private class MapSurfaceHost(
    context: Context,
    private val cameraState: CameraState,
    private val density: Float,
    private val archivePath: String?,
    private val onFrame: () -> Unit,
) {
    private val appContext = context.applicationContext
    private var handle = 0L
    private var surface: Surface? = null
    private var frameCallback: Choreographer.FrameCallback? = null

    /**
     * Written only from the main-thread [TextureView.SurfaceTextureListener] callbacks, so
     * the `mutableStateOf` needs no synchronisation.
     */
    var renderState: MapRenderState by mutableStateOf(MapRenderState.Initialising)
        private set

    /**
     * Whether the host is at least STARTED. Owned here rather than split across a
     * `pause()`/`resume()` pair so there is exactly one writer per input and one reconciler
     * ([syncFrameLoop]) deciding whether the callback should be posted. The orderings that
     * pair gets wrong — backgrounded before the texture arrives, texture destroyed while
     * stopped, ON_START before the handle exists — all collapse into "recompute the
     * predicate" here.
     */
    private var started = false

    /** Remembered so a surface created after the theme was set still starts in it. */
    private var dark = false
    private var muted = false

    /**
     * Remembered for the same reason as [dark]: `MapNative.create` deliberately takes no
     * layer arguments, so a surface created after the host chose its layers has to be told
     * about them before its first frame — otherwise the first resident set is tessellated
     * without them and immediately thrown away.
     */
    private var layers = LayerOptions()

    /**
     * Remembered for the same reason as [layers]. Previously this was sampled exactly once,
     * inside `onSurfaceTextureAvailable`, so going offline mid-session left the renderer
     * retrying and coming back left it pinned to cache.
     */
    private var online = true

    /**
     * Remembered for the same reason as [layers]: a fix taken before the surface existed
     * has to reach the renderer before its first frame, or the puck is missing until the
     * next one arrives a second later.
     */
    private var userPuck: UserPuck? = null

    /**
     * Where cached byte ranges live. External files rather than the cache dir: this is
     * large and expensive to rebuild, so it should not be the first thing the platform
     * reclaims, and external files are outside the 25 MB cloud-backup quota.
     */
    private val cacheDir: File by lazy {
        val root = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        File(root, CACHE_DIR_NAME).apply { mkdirs() }
    }

    val listener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            if (!MapNative.isAvailable) {
                Log.e(TAG, "libmap_renderer.so did not load; the map will not draw")
                renderState = MapRenderState.Unavailable(MapRenderState.Reason.RendererLibraryMissing)
                return
            }
            val created = Surface(texture)
            surface = created
            handle = MapNative.create(created, cacheDir.absolutePath, width, height, dark, muted, archivePath)
            if (handle == 0L) {
                Log.e(TAG, "the Vulkan renderer failed to start; see MapRenderer in logcat")
                created.release()
                surface = null
                renderState = MapRenderState.Unavailable(MapRenderState.Reason.RendererStartFailed)
                return
            }
            MapNative.setOnline(handle, online)
            // Before the first frame, so the very first resident set is tessellated with
            // the layers the host asked for instead of being built and then invalidated.
            MapNative.setLayers(handle, layers.poi, layers.transit, layers.poiKinds.joinToString(","))
            applyUserPuck()
            renderState = MapRenderState.Rendering
            syncFrameLoop()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            if (handle != 0L) MapNative.resize(handle, width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            teardown()
            // True: we release the SurfaceTexture, having already dropped the
            // ANativeWindow that pointed at it.
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    private fun syncFrameLoop() {
        val shouldRun = started && handle != 0L
        if (shouldRun) {
            if (frameCallback != null) return
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (handle == 0L) {
                        if (frameCallback === this) frameCallback = null
                        return
                    }
                    val viewport = cameraState.viewportDp
                    if (viewport != null) {
                        val position = cameraState.position
                        val drawn = MapNative.render(
                            handle,
                            position.target.longitude.toFloat(),
                            position.target.latitude.toFloat(),
                            position.zoom.toFloat(),
                            viewport.width,
                            viewport.height,
                            density,
                        )
                        if (drawn) onFrame()
                    }
                    // Re-post only while still the installed callback: teardown() and
                    // onStopped() null this out, and a callback already dispatched for this
                    // frame cannot be un-posted by removeFrameCallback.
                    if (frameCallback === this) Choreographer.getInstance().postFrameCallback(this)
                }
            }
            frameCallback = callback
            Choreographer.getInstance().postFrameCallback(callback)
        } else {
            frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
            frameCallback = null
        }
    }

    fun onStarted() {
        started = true
        syncFrameLoop()
    }

    fun onStopped() {
        started = false
        syncFrameLoop()
    }

    private fun teardown() {
        // Destroy before releasing the Surface: the native side waits for the GPU to go
        // idle and releases the ANativeWindow that points at it.
        if (handle != 0L) {
            MapNative.destroy(handle)
            handle = 0L
        }
        surface?.release()
        surface = null
        // Back to Initialising, not sticky-Unavailable: the surface is gone, and a
        // TextureView that is recreated (navigation, backgrounding) gets a fresh attempt.
        renderState = MapRenderState.Initialising
        // handle is now 0, so this is what removes the callback.
        syncFrameLoop()
    }

    fun dispose() = teardown()

    /**
     * Task-17 pick: placed labels intersecting [box] (Dp), restricted to
     * [layerIds] (our flat ids), in placement order. Parses the native
     * `\u0001`-joined rows; empty when the renderer isn't up or nothing hits.
     */
    fun pickLabels(box: DpRect, layerIds: Set<String>): List<PlacedLabel> {
        val h = handle
        if (h == 0L) return emptyList()
        return try {
            MapNative.pickLabels(h, box.left.value, box.top.value, box.right.value, box.bottom.value)
                .asSequence()
                .map { row -> row.split('') }
                .filter { parts -> parts.size == 6 && (layerIds.isEmpty() || parts[0] in layerIds) }
                .map { parts ->
                    PlacedLabel(
                        layerId = parts[0],
                        name = parts[1],
                        kind = parts[2],
                        position = GeoPoint(
                            longitude = parts[3].toDoubleOrNull() ?: 0.0,
                            latitude = parts[4].toDoubleOrNull() ?: 0.0,
                        ),
                        // Unsigned on the native side; ids never come close to the sign bit
                        // (an OSM id shifted left two is ~36 bits), so a Long is roomy.
                        featureId = parts[5].toLongOrNull() ?: 0L,
                    )
                }
                .toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun setPalette(dark: Boolean, muted: Boolean) {
        this.dark = dark
        this.muted = muted
        if (handle != 0L) MapNative.setPalette(handle, dark, muted)
    }

    fun setLayers(options: LayerOptions) {
        this.layers = options
        if (handle != 0L) {
            MapNative.setLayers(handle, options.poi, options.transit, options.poiKinds.joinToString(","))
        }
    }

    fun setOnline(online: Boolean) {
        this.online = online
        if (handle != 0L) MapNative.setOnline(handle, online)
    }

    fun setUserPuck(puck: UserPuck?) {
        this.userPuck = puck
        applyUserPuck()
    }

    private fun applyUserPuck() {
        if (handle == 0L) return
        val puck = userPuck
        if (puck == null) {
            MapNative.clearUserPuck(handle)
        } else {
            MapNative.setUserPuck(
                handle,
                puck.position.longitude.toFloat(),
                puck.position.latitude.toFloat(),
                puck.bearing ?: 0f,
                puck.bearing != null,
            )
        }
    }

    private companion object {
        const val TAG = "VulkanMapSurface"
        const val CACHE_DIR_NAME = "vectortilecache"
    }
}
