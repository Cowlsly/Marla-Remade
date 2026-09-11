package com.vayunmathur.library.map

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpRect
import java.io.File

/**
 * Draws the vector basemap into an arbitrary [Surface].
 *
 * This is the public, Compose-free boundary of the renderer: [MapNative] stays internal and
 * nothing here knows about `TextureView`, `SurfaceView` or Android Auto. Anything that can
 * produce a [Surface] and a pixel size can host the map — [VulkanMapSurface] wraps this for
 * Compose, and a car app can hand it the `Surface` out of `SurfaceContainer`.
 *
 * ## Threading
 *
 * **Every method on this class, and every property set on it, must be called on the main
 * thread**, including construction. The renderer is not safe to drive from two threads at
 * once, and the frame loop runs on the main thread's [Choreographer], so a call from
 * anywhere else races the frame currently being recorded. This is a hard requirement, not a
 * convention — there is no lock behind it.
 *
 * That costs less than it sounds like: the expensive work (range fetch, inflate, MVT decode,
 * tessellation) happens on worker threads inside the native side, so a frame on this side
 * only uploads finished meshes and records a command buffer. Android Auto's
 * `SurfaceCallback` is dispatched on the main thread, so the car path satisfies this without
 * doing anything.
 *
 * ## Lifecycle
 *
 * ```kotlin
 * val renderer = SurfaceMapRenderer(context, density = dpi / 160f)
 * renderer.setPalette(dark = true, muted = false)   // fine before the surface exists
 * renderer.attachSurface(surface, widthPx, heightPx)
 * renderer.camera = CameraPosition(GeoPoint(lon, lat), zoom = 14.0)
 * renderer.setRoute(routePoints)                    // fine before the surface exists too
 * renderer.start()
 * // …
 * renderer.stop()
 * renderer.detachSurface()   // the caller still owns and releases `surface`
 * renderer.destroy()
 * ```
 *
 * The surface is deliberately not owned here: [detachSurface] destroys the native renderer
 * (which waits for the GPU to go idle and drops the `ANativeWindow`) but never calls
 * [Surface.release]. Whoever created the `Surface` releases it, and only after
 * [detachSurface] has returned.
 *
 * ## State set before the surface exists
 *
 * [MapNative.create] deliberately takes no layer, puck or connectivity arguments, so
 * everything set through [setPalette], [setLayers], [setOnline], [setUserPuck],
 * [setRoute] and [setRegionMask] is remembered and replayed into the native renderer
 * inside [attachSurface], **before the first frame**. Without that the first resident set
 * is tessellated with the wrong layers and immediately thrown away, the puck is missing
 * until the next fix arrives a second later, the route is missing until the host happens
 * to set it again, and a surface created while offline stays pinned to cache. Setting any
 * of them while a surface is attached applies immediately.
 *
 * @param context any [Context]; only the application context is retained.
 * @param density pixels per dp for [surface][attachSurface]'s display — `dpi / 160f` on
 *   Android Auto, `LocalDensity.current.density` in Compose. The renderer scales line
 *   widths, icons and text with it, so a wrong value gives a legible-but-wrong map rather
 *   than a visible failure.
 * @param archivePath overrides the built-in archive URL for local iteration. `null` or empty
 *   keeps the remote archive.
 * @param onFrame called on the main thread after each frame that was actually presented.
 */
class SurfaceMapRenderer(
    context: Context,
    private val density: Float,
    private val archivePath: String? = null,
    private val onFrame: () -> Unit = {},
) {

    /**
     * Convenience for a host whose surface already exists at construction time: identical to
     * building the renderer and immediately calling [attachSurface]. Check [renderState]
     * afterwards — creation is synchronous, so it is already final when this returns.
     *
     * The frame loop is *not* started; call [start].
     */
    constructor(
        context: Context,
        surface: Surface,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        archivePath: String? = null,
        onFrame: () -> Unit = {},
    ) : this(context, density, archivePath, onFrame) {
        attachSurface(surface, widthPx, heightPx)
    }

    private val appContext = context.applicationContext
    private var handle = 0L
    private var widthPx = 0
    private var heightPx = 0

    /**
     * One callback for the life of the renderer, posted and re-posted rather than rebuilt.
     * [posted] carries the "is it installed" state that the identity of a per-run callback
     * object used to.
     */
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos -> onVsync(frameTimeNanos) }
    private var posted = false

    /** Whether the posted callback is scheduled for later rather than the next vsync. */
    private var postedDelayed = false

    /**
     * [System.nanoTime] when something last changed what the next frame would draw.
     *
     * The loop keeps drawing until [IDLE_GRACE_NANOS] after this, rather than stopping on the
     * frame after the last change — see [stillChanging].
     */
    private var lastChangeNanos = System.nanoTime()

    /**
     * The camera and viewport the last frame was actually drawn from, so a change that reaches
     * the renderer without passing through a setter is still noticed. The Compose path *pulls*
     * its camera out of [composeCamera] inside [renderFrame] and nothing tells us when a
     * gesture or animation moves it, so this is the backstop under the push channel
     * `VulkanMapSurface` installs.
     */
    private var lastPosition: CameraPosition? = null
    private var lastWidthDp = 0f
    private var lastHeightDp = 0f

    /**
     * Where the map is looking, and which way is up. Read once per frame on the main
     * thread, so a host may write it as often as it likes — a camera animation running at
     * 60 Hz costs the assignment plus an [invalidate], which is a timestamp and an
     * already-posted check.
     *
     * [CameraPosition.bearing] rotates the map for heading-up navigation. It is only
     * usable through this property: the Compose path takes its camera from a
     * [CameraState], whose [Projection] is north-up only.
     *
     * Ignored while [composeCamera] is set, which is the Compose path only.
     */
    var camera: CameraPosition = CameraPosition()
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Compose reads its camera out of a [CameraState] inside the frame callback, so that a
     * gesture or animation lands in the very next frame rather than a snapshot-propagation
     * later. Setting this makes the loop take both the camera and the dp viewport from that
     * state (skipping any frame whose viewport is not measured yet) instead of from [camera]
     * and the attached surface size.
     *
     * Because this is a *pull*, a host that sets it must also push: nothing here observes the
     * [CameraState], so a gesture or animation moving it while the loop is idle would go
     * unnoticed. [VulkanMapSurface] calls [invalidate] from a `snapshotFlow` over the camera
     * for exactly that reason.
     */
    internal var composeCamera: CameraState? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Whether this surface is drawing, or why it is not.
     *
     * Backed by Compose state so [VulkanMapSurface] can show a fallback without polling;
     * non-Compose hosts can just read it after [attachSurface] returns, since the native
     * renderer is created synchronously there. Written only on the main thread, so it needs
     * no synchronisation.
     */
    var renderState: MapRenderState by mutableStateOf(MapRenderState.Initialising)
        private set

    /**
     * Whether the host wants frames. Owned here rather than split across a `pause()`/
     * `resume()` pair so there is exactly one writer per input and one reconciler
     * ([syncFrameLoop]) deciding whether the callback should be posted. The orderings that
     * pair gets wrong — stopped before the surface arrives, surface destroyed while stopped,
     * [start] before the handle exists — all collapse into "recompute the predicate" here.
     */
    private var started = false

    /** Remembered so a surface attached after the theme was set still starts in it. */
    private var dark = false
    private var muted = false

    /** Remembered so the first resident set is tessellated with the layers the host asked for. */
    private var layers = LayerOptions()

    /** Remembered so a surface attached while offline is not left retrying, or vice versa. */
    private var online = true

    /** Remembered so a fix taken before the surface existed is drawn in the first frame. */
    private var userPuck: UserPuck? = null

    /**
     * The app pins to draw, remembered so a set pushed before the surface existed — or one that
     * outlives a surface being recreated — is still there in the first frame, the same way
     * [userPuck] and [routeSegments] are.
     */
    private var markers: List<MapMarker> = emptyList()

    /**
     * The simulated transit vehicles to draw, remembered on the same terms as [markers] so a set
     * pushed before the surface existed — or one that outlives a surface being recreated — is still
     * there in the first frame. Kept apart from [markers] because the host pushes the two on
     * different cadences (vehicles at ~1 Hz, pins on a tap/search).
     */
    private var vehicles: List<MapMarker> = emptyList()

    /**
     * The route to draw, and how, remembered so a route set before the surface existed —
     * or one that outlives a surface being recreated — is still there in the first frame.
     *
     * Kept as the coloured segments rather than as anything native, because the native mesh
     * dies with the renderer and this has to be able to rebuild it.
     */
    private var routeSegments: List<RouteSegment>? = null
    private var routeStyle = RouteStyle()

    /**
     * The last-pushed live-traffic colour table (`component_id`s and their ARGB), remembered so
     * a push that arrived before the surface existed — or that has to outlive the surface being
     * recreated — is re-applied in the first frame, the same way [route] is. `null` means the
     * overlay has nothing to draw (never pushed, or cleared).
     */
    private var trafficSpeeds: Pair<LongArray, IntArray>? = null

    /** The region to mask and which rung of the admin stack it is, or `null` for no mask. */
    private var regionProbe: RegionMask? = null

    /**
     * Whether [regionProbe] has been matched to a region yet.
     *
     * The match needs the region's tiles to be resident, and a selection usually arrives before
     * they are - tapping a city label recentres the map, so the tiles under the new camera are
     * still in flight. So an unresolved probe is retried each frame until it lands rather than
     * being dropped, which is the difference between the mask appearing and the mask silently
     * never appearing.
     */
    private var regionResolved = true

    /**
     * Where cached byte ranges live. External files rather than the cache dir: this is
     * large and expensive to rebuild, so it should not be the first thing the platform
     * reclaims, and external files are outside the 25 MB cloud-backup quota.
     */
    private val cacheDir: File by lazy {
        val root = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        File(root, CACHE_DIR_NAME).apply { mkdirs() }
    }

    /**
     * Bring the native renderer up on [surface] at [widthPx] x [heightPx] pixels, replaying
     * the deferred theme, layers, connectivity, puck and region mask before any frame is
     * drawn. [renderState] is final when this returns.
     *
     * Any previously attached surface is torn down first. The caller keeps ownership of
     * [surface] and must not release it until after [detachSurface] or [destroy].
     */
    fun attachSurface(surface: Surface, widthPx: Int, heightPx: Int) {
        if (handle != 0L) detachSurface()
        this.widthPx = widthPx
        this.heightPx = heightPx
        // A new native renderer has drawn nothing, so the previous surface's snapshot must not
        // let the camera diff conclude the first frame changed nothing.
        lastPosition = null
        if (!MapNative.isAvailable) {
            Log.e(TAG, "libmap_renderer.so did not load; the map will not draw")
            renderState = MapRenderState.Unavailable(MapRenderState.Reason.RendererLibraryMissing)
            return
        }
        handle = MapNative.create(surface, cacheDir.absolutePath, widthPx, heightPx, dark, muted, archivePath)
        if (handle == 0L) {
            Log.e(TAG, "the Vulkan renderer failed to start; see MapRenderer in logcat")
            renderState = MapRenderState.Unavailable(MapRenderState.Reason.RendererStartFailed)
            return
        }
        MapNative.setOnline(handle, online)
        // Before the first frame, so the very first resident set is tessellated with
        // the layers the host asked for instead of being built and then invalidated.
        MapNative.setLayers(handle, layers.poi, layers.transit, layers.poiKinds.joinToString(","))
        MapNative.setTrafficEnabled(handle, layers.traffic)
        applyUserPuck()
        applyRoute()
        applyRegionMask()
        applyTraffic()
        applyMarkers()
        applyVehicles()
        renderState = MapRenderState.Rendering
        syncFrameLoop()
        // The whole deferred set was just replayed into a brand-new native renderer, and the
        // resident set is empty, so this surface owes a frame whatever the loop was doing.
        invalidate()
    }

    /** Tell the renderer the attached surface is now [widthPx] x [heightPx] pixels. */
    fun resize(widthPx: Int, heightPx: Int) {
        this.widthPx = widthPx
        this.heightPx = heightPx
        if (handle != 0L) MapNative.resize(handle, widthPx, heightPx)
        invalidate()
    }

    /**
     * Destroy the native renderer and stop drawing, leaving this instance reusable: a later
     * [attachSurface] brings it back up with all the deferred state intact.
     *
     * Does not release the [Surface] — that is the caller's, and releasing it before this
     * returns would pull the window out from under a GPU that has not gone idle yet.
     */
    fun detachSurface() {
        if (handle != 0L) {
            MapNative.destroy(handle)
            handle = 0L
        }
        // Back to Initialising, not sticky-Unavailable: the surface is gone, and a surface
        // that is recreated (navigation, backgrounding) gets a fresh attempt.
        renderState = MapRenderState.Initialising
        // handle is now 0, so this is what removes the callback.
        syncFrameLoop()
    }

    /** Start driving frames, once there is a surface to draw into. Idempotent. */
    fun start() {
        started = true
        syncFrameLoop()
        // A host coming back on screen owes a frame: the surface may have been recreated, and
        // anything pushed while stopped never reached a frame.
        invalidate()
    }

    /**
     * Stop driving frames without tearing the renderer down, for a host that is off screen.
     * Idempotent.
     *
     * This stops *rendering* only. In-flight tile fetches on the native side are not paused,
     * and that is where the data cost is, so this does not fully quiesce the map.
     */
    fun stop() {
        started = false
        syncFrameLoop()
    }

    /** [stop] plus [detachSurface]: this host is done with the map. */
    fun destroy() {
        started = false
        detachSurface()
    }

    /**
     * Draw exactly one frame, for a host driving its own loop instead of using
     * [start]/[stop]. Returns whether a frame was actually presented — false when there is
     * no renderer, no measured viewport, or the swapchain needed rebuilding.
     *
     * Uses `System.nanoTime()` as the animation clock; the Choreographer loop passes its own
     * `frameTimeNanos` through the [renderFrame]`(Long)` overload instead.
     */
    fun renderFrame(): Boolean = renderFrame(System.nanoTime())

    /**
     * As [renderFrame], but with the frame clock supplied by the caller — the Choreographer's
     * `frameTimeNanos`, which the renderer forwards to shaders so animation is tied to the
     * presented frame rather than to wall-clock jitter.
     */
    fun renderFrame(frameTimeNanos: Long): Boolean {
        if (handle == 0L) return false
        val position: CameraPosition
        val widthDp: Float
        val heightDp: Float
        // Forced to zero on the Compose path. `Projection` — and therefore every
        // `MapMarker`, pin and cluster positioned through it — is north-up only for bearing, so
        // honouring a bearing there would rotate the basemap out from under overlays that
        // did not rotate with it. Tilt is different: `Projection` is now pitch-aware (ray/plane),
        // so pitch *is* honoured on both paths and overlays follow it. Bearing is reachable
        // only through [camera], on a surface with no Compose overlays above it.
        val bearing: Float
        val state = composeCamera
        if (state != null) {
            val viewport = state.viewportDp ?: return false
            position = state.position
            widthDp = viewport.width
            heightDp = viewport.height
            bearing = 0f
        } else {
            if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return false
            position = camera
            widthDp = widthPx / density
            heightDp = heightPx / density
            bearing = position.bearing.toFloat()
        }
        // The backstop for a camera that moved without telling us: the Compose path pulls its
        // camera out of a CameraState here, and a gesture that somehow reached it without
        // waking the loop would otherwise draw one frame and settle mid-movement.
        if (position != lastPosition || widthDp != lastWidthDp || heightDp != lastHeightDp) {
            lastPosition = position
            lastWidthDp = widthDp
            lastHeightDp = heightDp
            lastChangeNanos = System.nanoTime()
        }
        val drawn = MapNative.render(
            handle,
            position.target.longitude.toFloat(),
            position.target.latitude.toFloat(),
            position.zoom.toFloat(),
            bearing,
            position.pitch.toFloat(),
            widthDp,
            heightDp,
            density,
            frameTimeNanos,
        )
        if (drawn) onFrame()
        if (!regionResolved) applyRegionMask()
        return drawn
    }

    private fun syncFrameLoop() {
        if (started && handle != 0L) requestFrame() else cancelFrame()
    }

    /**
     * Ask for a frame because something changed, and re-arm the idle grace.
     *
     * The renderer draws **on demand**, not every vsync: rendering a still map at 60 Hz did
     * full Vulkan work on the main thread for an identical picture, which is what made the
     * device hot. Every setter here calls this, and so must anything outside that mutates what
     * the renderer would draw. It is public and cheap for that reason — a host that is unsure
     * whether a change is already covered should call it, because the cost of a wasted frame
     * is one frame and the cost of a missed one is a map that has silently stopped updating.
     *
     * Main thread, like everything else here. Idempotent within a frame.
     */
    fun invalidate() {
        lastChangeNanos = System.nanoTime()
        requestFrame()
    }

    private fun requestFrame() {
        if (!started || handle == 0L) return
        if (posted) {
            // Already scheduled for the next vsync — nothing to do.
            if (!postedDelayed) return
            // Scheduled for *later*, for work that was not urgent when it was posted. Something
            // has changed since, so pull it forward rather than making a camera move wait out a
            // retry backoff that can be seconds long.
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        posted = true
        postedDelayed = false
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * Ask for one frame [delayMillis] from now, for work that is pending but not yet due —
     * today, a failed tile waiting out its retry backoff.
     *
     * Posting it immediately would spin through the whole backoff at 60fps, and not posting it
     * at all would leave the tile missing until the user happened to move the camera. Any
     * [invalidate] in the meantime supersedes it.
     */
    private fun requestFrameDelayed(delayMillis: Long) {
        if (posted || !started || handle == 0L) return
        posted = true
        postedDelayed = true
        Choreographer.getInstance().postFrameCallbackDelayed(frameCallback, delayMillis)
    }

    private fun cancelFrame() {
        if (!posted) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        posted = false
        postedDelayed = false
    }

    private fun onVsync(frameTimeNanos: Long) {
        // Cleared first, so a re-entrant invalidate() from a setter called inside onFrame()
        // schedules the next frame rather than being swallowed as already-posted.
        posted = false
        postedDelayed = false
        // stop() and detachSurface() cannot un-post a callback Choreographer has already
        // dispatched for this frame, so the predicate is re-checked here rather than trusted.
        if (!started || handle == 0L) return
        val drawn = renderFrame(frameTimeNanos)
        // A frame that did not present has not shown the change that asked for it: the
        // swapchain was rebuilt, or the viewport is not measured yet. Either way, try again.
        if (!drawn) {
            requestFrame()
            return
        }
        if (System.nanoTime() - lastChangeNanos < IDLE_GRACE_NANOS || !regionResolved) {
            requestFrame()
            return
        }
        // Nothing local is changing, so the native side decides: draw now, draw later, or stop.
        val delay = MapNative.nextFrameDelayMillis(handle)
        when {
            delay == 0L -> requestFrame()
            delay > 0L -> requestFrameDelayed(delay)
        }
    }

    /** Switch palette: light or dark, muted or not. Free — only a push constant changes. */
    fun setPalette(dark: Boolean, muted: Boolean) {
        this.dark = dark
        this.muted = muted
        if (handle != 0L) MapNative.setPalette(handle, dark, muted)
        invalidate()
    }

    /**
     * Turn the optional POI, transit and traffic layers on or off. Not free — see
     * [LayerOptions]. A call that changes nothing does nothing.
     */
    fun setLayers(options: LayerOptions) {
        this.layers = options
        if (handle != 0L) {
            MapNative.setLayers(handle, options.poi, options.transit, options.poiKinds.joinToString(","))
            MapNative.setTrafficEnabled(handle, options.traffic)
        }
        invalidate()
    }

    /**
     * Push the live-traffic colour table: [ids] holds each segment's `component_id` and
     * [argbColors] the fully-resolved ARGB to draw it, index for index. The host owns the
     * theme, so the colours are final. Replaces the whole table each call; a segment whose id
     * is absent draws nothing (the basemap road shows through). Cheap — a pure recolour, no
     * tessellation — so it is safe to drive from a camera-idle callback.
     *
     * Remembered so it survives the surface being recreated. Has no visible effect unless the
     * traffic layer is enabled through [setLayers]/[LayerOptions.traffic].
     */
    fun setTrafficSpeeds(ids: LongArray, argbColors: IntArray) {
        this.trafficSpeeds = ids to argbColors
        applyTraffic()
        invalidate()
    }

    /** Clear the live-traffic overlay so it draws nothing until the next [setTrafficSpeeds]. */
    fun clearTraffic() {
        this.trafficSpeeds = null
        applyTraffic()
        invalidate()
    }

    /**
     * Tell the renderer whether the device is online. Offline it serves stale cached ranges
     * instead of attempting a request, so a previously-viewed area keeps drawing.
     */
    fun setOnline(online: Boolean) {
        this.online = online
        if (handle != 0L) MapNative.setOnline(handle, online)
        invalidate()
    }

    /**
     * Show the user's location, drawn inside the renderer's own frame so it stays glued to
     * the ground while the map moves. `null` draws nothing.
     */
    fun setUserPuck(puck: UserPuck?) {
        this.userPuck = puck
        applyUserPuck()
        invalidate()
    }

    /**
     * Replace the app's pins with [markers], drawn by the renderer as billboarded sprites so they
     * pan and tilt in lock-step with the basemap instead of trailing it the way a Compose overlay
     * does. An empty list clears them. Cheap: the geometry is the shared unit quad, so this is a
     * pure state push, no tessellation.
     */
    fun setMarkers(markers: List<MapMarker>) {
        this.markers = markers
        applyMarkers()
        invalidate()
    }

    /**
     * Replace the simulated transit vehicles with [vehicles], drawn by the renderer as billboarded
     * sprites through the same path as [setMarkers]. An empty list clears them. Cheap: the geometry
     * is the shared unit quad, so this is a pure state push, no tessellation.
     *
     * Separate from [setMarkers] so the host's ~1 Hz vehicle recompute replaces only the vehicles
     * and leaves the app pins untouched; drive it from the vehicle ticker rather than merging it
     * into the pin set.
     */
    fun setVehicles(vehicles: List<MapMarker>) {
        this.vehicles = vehicles
        applyVehicles()
        invalidate()
    }

    /**
     * The id of the pin under a tap, or `0` when the tap hit no pin. [xDp]/[yDp] are Dp from the
     * viewport top-left. Reads the renderer's id buffer (see the native `pickAt`), so it stays
     * correct under tilt and never trails the basemap on a pan, unlike a Compose CPU hit-test.
     *
     * The returned value is whatever [MapMarker.id] the host set, so it maps the tap back to its
     * own feature. `internal` because it is wired through [Projection.pickMarker] like the label
     * pick, so a caller without a live renderer gets `0` rather than a dead handle.
     */
    internal fun pickAt(xDp: Float, yDp: Float): Long {
        val h = handle
        if (h == 0L) return 0L
        return try {
            MapNative.pickAt(h, xDp, yDp)
        } catch (_: Throwable) {
            0L
        }
    }

    /** Dim everything outside the region [mask] names, or clear the mask with `null`. */
    fun setRegionMask(mask: RegionMask?) {
        this.regionProbe = mask
        this.regionResolved = false
        applyRegionMask()
        invalidate()
    }

    /**
     * Draw [points] as a single-colour navigation route, over the basemap and under the
     * puck. `null` or fewer than two distinct points draws nothing, which is how a route is
     * cleared. The convenience path for a host that wants one line in one colour — Android
     * Auto, which has no view hierarchy to hang a Compose overlay in.
     *
     * Main thread, like everything else here. Not free, but paid once per route rather
     * than once per frame: the native side tessellates the polyline and uploads it here,
     * and never rebuilds it — the mesh is zoom-independent, so a navigation session costs
     * one tessellation however long the drive or however much the driver zooms. Setting
     * the same route again does re-tessellate, so drive this from a state change rather
     * than from a per-frame callback.
     */
    fun setRoute(points: List<GeoPoint>?, style: RouteStyle = RouteStyle()) {
        this.routeSegments = points?.let { listOf(RouteSegment(it, DEFAULT_ROUTE_COLOR)) }
        this.routeStyle = style
        applyRoute()
        invalidate()
    }

    /**
     * Draw a multi-segment, per-segment-coloured route (see [RouteOverlay]), over the
     * basemap and under the puck. `null` or an all-empty overlay draws nothing, which is
     * how a route is cleared. This is what the phone pushes: the traffic-band / transit /
     * travelled-grey colouring resolved on the device into one coloured segment per run.
     *
     * Same cost model as the single-colour [setRoute]: one tessellation per push, never
     * per frame or per zoom step.
     */
    fun setRoute(overlay: RouteOverlay?) {
        this.routeSegments = overlay?.segments
        this.routeStyle = overlay?.style ?: RouteStyle()
        applyRoute()
        invalidate()
    }

    /**
     * Task-17 pick: placed labels intersecting [box] (Dp), restricted to
     * [layerIds] (our flat ids), in placement order. Parses the native
     * `\u0001`-joined rows; empty when the renderer isn't up or nothing hits.
     */
    internal fun pickLabels(box: DpRect, layerIds: Set<String>): List<PlacedLabel> {
        val h = handle
        if (h == 0L) return emptyList()
        return try {
            MapNative.pickLabels(h, box.left.value, box.top.value, box.right.value, box.bottom.value)
                .asSequence()
                .map { row -> row.split('\u0001') }
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

    private fun applyRegionMask() {
        if (handle == 0L) return
        val probe = regionProbe
        if (probe == null) {
            MapNative.clearRegionMask(handle)
            regionResolved = true
            return
        }
        val id = MapNative.setRegionMask(
            handle,
            probe.position.longitude.toFloat(),
            probe.position.latitude.toFloat(),
            probe.level.min,
            probe.level.max,
        )
        regionResolved = id != 0L
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

    private fun applyMarkers() {
        if (handle == 0L) return
        val pins = markers
        if (pins.isEmpty()) {
            MapNative.clearMarkers(handle)
            return
        }
        // Three parallel bulk arrays, the convention the native side reads: ids, then interleaved
        // lon/lat, then icon ids. Packed once here rather than crossing the boundary per pin.
        val ids = LongArray(pins.size)
        val lonLat = FloatArray(pins.size * 2)
        val icons = IntArray(pins.size)
        for (i in pins.indices) {
            val pin = pins[i]
            ids[i] = pin.id
            lonLat[i * 2] = pin.position.longitude.toFloat()
            lonLat[i * 2 + 1] = pin.position.latitude.toFloat()
            icons[i] = pin.icon
        }
        MapNative.setMarkers(handle, ids, lonLat, icons)
    }

    private fun applyVehicles() {
        if (handle == 0L) return
        val vs = vehicles
        if (vs.isEmpty()) {
            MapNative.clearVehicles(handle)
            return
        }
        // Three parallel bulk arrays, the convention the native side reads: ids, then interleaved
        // lon/lat, then icon ids. Packed once here rather than crossing the boundary per vehicle.
        val ids = LongArray(vs.size)
        val lonLat = FloatArray(vs.size * 2)
        val icons = IntArray(vs.size)
        for (i in vs.indices) {
            val v = vs[i]
            ids[i] = v.id
            lonLat[i * 2] = v.position.longitude.toFloat()
            lonLat[i * 2 + 1] = v.position.latitude.toFloat()
            icons[i] = v.icon
        }
        MapNative.setVehicles(handle, ids, lonLat, icons)
    }

    private fun applyRoute() {
        if (handle == 0L) return
        // Drawable segments only: fewer than two points strokes nothing, and dropping them
        // here keeps the native side's per-segment ranges aligned with the colours.
        val drawable = routeSegments?.filter { it.points.size >= 2 }
        if (drawable.isNullOrEmpty()) {
            MapNative.clearRoute(handle)
            return
        }
        // Flat lon/lat pairs across every segment, plus a point count and colour per
        // segment: three bulk arrays cross JNI instead of one call per point, which for a
        // cross-city route is thousands of crossings saved. Built here rather than held,
        // because a route is set once and this is not a per-frame path.
        val flat = FloatArray(drawable.sumOf { it.points.size } * 2)
        val lengths = IntArray(drawable.size)
        val colors = IntArray(drawable.size)
        var at = 0
        drawable.forEachIndexed { i, segment ->
            lengths[i] = segment.points.size
            colors[i] = segment.color.toArgb()
            for (point in segment.points) {
                flat[at * 2] = point.longitude.toFloat()
                flat[at * 2 + 1] = point.latitude.toFloat()
                at++
            }
        }
        MapNative.setRoute(
            handle,
            flat,
            lengths,
            colors,
            routeStyle.width.value,
            routeStyle.casingWidth.value,
            routeStyle.casingColor.toArgb(),
        )
    }

    private fun applyTraffic() {
        if (handle == 0L) return
        val speeds = trafficSpeeds
        if (speeds == null) {
            MapNative.clearTraffic(handle)
        } else {
            MapNative.setTrafficSpeeds(handle, speeds.first, speeds.second)
        }
    }

    private companion object {
        const val TAG = "SurfaceMapRenderer"
        const val CACHE_DIR_NAME = "vectortilecache"

        /**
         * How long the frame loop keeps running after the last change it noticed.
         *
         * The renderer stops drawing when nothing is changing, so the cost of failing to
         * notice a change is a map that has frozen — far worse than the frames this wastes.
         * A quarter of a second is long enough to cover an invalidation that arrives a frame
         * or two late (the `snapshotFlow` push channel is one such) and short enough that an
         * idle map settles to zero frames while the user is still looking at it. It costs
         * nothing during an interaction, where every frame re-arms it.
         */
        val IDLE_GRACE_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250)

        /**
         * The fill the single-colour [setRoute] convenience paints: the car's `#1A73E8`,
         * the one route colour in this app authored against a real rendering (see
         * [RouteStyle]). A per-segment route carries its own colours instead.
         */
        val DEFAULT_ROUTE_COLOR = Color(0xFF1A73E8)
    }
}
