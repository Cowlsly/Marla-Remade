package com.vayunmathur.library.map

import android.view.Surface

/**
 * The JNI surface of the Vulkan renderer in `library/map/src/main/rust`.
 *
 * Deliberately narrow and nothing per-feature: Kotlin creates and destroys the renderer
 * for a [Surface], resizes it, reports connectivity and theme, sets the handful of pieces
 * of state the renderer draws from, and hands it **one camera snapshot per frame**. Tile
 * selection, fetching, decode, tessellation and drawing all happen on the native side, so
 * the boundary is crossed a handful of times a frame rather than thousands.
 *
 * [handle] values are opaque pointers owned by the native side. Every method tolerates
 * `0`, which is what [create] returns on failure — so a device without a working Vulkan
 * driver degrades to a blank map rather than crashing the app.
 */
internal object MapNative {

    /**
     * Whether `libmap_renderer.so` loaded.
     *
     * False on a device whose ABI we did not build for. The caller shows the background
     * colour rather than crashing.
     */
    val isAvailable: Boolean = try {
        System.loadLibrary("map_renderer")
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Bring up Vulkan on [surface]. Returns an opaque handle, or 0 on failure.
     *
     * [cacheDir] is where the range cache lives. It should be external files rather than
     * the cache dir: like the archive `maps` downloads, it is large and expensive to
     * rebuild, so it should not be the first thing the platform reclaims.
     *
     * [archivePath] overrides the built-in archive URL for local iteration (e.g. a
     * freshly rebuilt `na.mamaps` pushed to the device). `null` or empty keeps the
     * remote `BASEMAP_ARCHIVE_URL` (`planet.mamaps`).
     */
    external fun create(
        surface: Surface,
        cacheDir: String,
        width: Int,
        height: Int,
        dark: Boolean,
        muted: Boolean,
        archivePath: String?,
    ): Long

    /**
     * Draw one frame. Returns false when the frame was skipped because the swapchain
     * needed rebuilding — the next callback will draw.
     *
     * The camera is passed as primitives, not an object, so the call allocates nothing.
     * Longitude and latitude are `Float` rather than `Double` deliberately: a float has
     * ~7 significant digits, which at the equator is about a centimetre, and the camera
     * is a viewing position rather than a measurement.
     *
     * [bearing] is degrees clockwise from north for whatever points up the screen. Zero
     * is north-up, which is every frame the Compose path draws; the native side
     * short-circuits it, so a north-up frame composes exactly the matrices it always did.
     */
    external fun render(
        handle: Long,
        centerLon: Float,
        centerLat: Float,
        zoom: Float,
        bearing: Float,
        widthDp: Float,
        heightDp: Float,
        density: Float,
    ): Boolean

    external fun resize(handle: Long, width: Int, height: Int)

    /**
     * Switch palette: light or dark, muted or not.
     *
     * Free: colour reaches the GPU as a push constant and the layer set is identical, so
     * nothing is re-tessellated or re-uploaded. That is why this is a runtime call rather
     * than a [create] argument — the map can follow the system theme.
     */
    external fun setPalette(handle: Long, dark: Boolean, muted: Boolean)

    /**
     * Turn the optional layers (POI, transit) on or off.
     *
     * Not free, unlike [setPalette]. Both layers are gated at tessellation time so that
     * leaving them off costs nothing, which means changing either invalidates every mesh
     * already on the GPU. The native side bumps a generation counter; the render loop then
     * sees the resident tiles are stale and re-tessellates them on the existing worker
     * pool, reading the archive it already has. Nothing is refetched or evicted, and the
     * old meshes keep drawing until the new ones land.
     *
     * A call that changes nothing does nothing, so this is safe to drive from a
     * `LaunchedEffect` that may re-run for unrelated reasons.
     *
     * [kinds] is a comma-separated list of archive kind names narrowing POI to those kinds;
     * empty draws them all. A name the schema does not know is ignored.
     */
    external fun setLayers(handle: Long, poi: Boolean, transit: Boolean, kinds: String)

    /**
     * Tell the renderer whether the device is online. When offline it serves stale cached
     * ranges instead of attempting a request, so a previously-viewed area keeps drawing.
     */
    external fun setOnline(handle: Long, online: Boolean)

    /**
     * Show the user-location puck at [lon]/[lat], drawn by the renderer inside the same
     * frame as the basemap.
     *
     * Free in the same sense as [setPalette]: the quad is already on the GPU and
     * everything that varies about the puck reaches it as a push constant, so nothing is
     * re-tessellated or re-uploaded. That is why a fix is pushed in out of band rather
     * than being an argument on [render] — a fix arrives at about 1 Hz while [render]
     * runs at 60.
     *
     * [lon] and [lat] are `Float` for the same reason the camera's are (see [render]).
     * [bearing] is degrees clockwise from north and is only read when [hasBearing] is
     * true; without it the dot draws and the bearing cone does not.
     */
    external fun setUserPuck(
        handle: Long,
        lon: Float,
        lat: Float,
        bearing: Float,
        hasBearing: Boolean,
    )

    /** Take the puck away: no fix, or a host that stopped asking for one. */
    external fun clearUserPuck(handle: Long)

    /**
     * Draw a navigation route line over the basemap and under the puck.
     *
     * [points] is a flat `[lon0, lat0, lon1, lat1, …]` array — one array rather than a
     * list of objects because a route is thousands of points and a per-point crossing is
     * exactly what this boundary exists to avoid. `Float` for the same reason [render]'s
     * coordinates are. A trailing odd element is ignored, and fewer than two distinct
     * points draws nothing.
     *
     * One polyline and one colour, which is what the consumer draws — see [RouteStyle].
     *
     * Not free, but paid **once**: the native side tessellates the polyline on the calling
     * thread and uploads it. It never rebuilds it after that — the mesh is normalised into
     * the route's own bounding square, and Web Mercator is a pure scale in zoom, so the
     * same vertices are correct at every zoom and only the matrix changes per frame. A
     * navigation session therefore costs one tessellation, not one per zoom step, which is
     * the difference that matters on a car's power budget.
     *
     * An array that cannot be read leaves the route **unchanged** rather than clearing it:
     * a bad frame of route data must not blank a route being followed.
     *
     * Widths are Dp and colours are ARGB, like everything else here.
     */
    external fun setRoute(
        handle: Long,
        points: FloatArray,
        widthDp: Float,
        casingDp: Float,
        color: Int,
        casingColor: Int,
    )

    /** Take the route away: navigation ended, or the host cleared it. */
    external fun clearRoute(handle: Long)

    /**
     * Dim everything outside the region containing [lon]/[lat], and return which region
     * that is.
     *
     * Takes a point rather than a region id because nothing in the archive links a place to
     * its outline: a city is a `places` node with its own OSM id, while its boundary is a
     * `boundaries` relation, and OSM does not oblige the node to belong to the relation.
     * Containment is the link.
     *
     * [levelMin]/[levelMax] bound the OSM `admin_level` band the selection means, inclusive.
     * They are required because containment alone is ambiguous: every label sits inside a
     * whole stack of regions, so without a level a tap on a state resolves to whichever
     * county its label happens to sit in.
     *
     * Returns the region's OSM relation id, or 0 when no resident tile covers the point.
     * Callers can tell "there is no region here" from "the tiles have not landed yet" only by
     * retrying, which is why the id comes back rather than nothing.
     */
    external fun setRegionMask(handle: Long, lon: Float, lat: Float, levelMin: Int, levelMax: Int): Long

    /** Take the region mask away: the details sheet closed, or the selection moved on. */
    external fun clearRegionMask(handle: Long)

    /**
     * Pick placed labels (task 17): the last frame's placed symbol labels
     * whose screen boxes intersect the query box, in placement order.
     *
     * All four box edges are Dp from the viewport top-left. Returns one
     * `String` per hit, fields joined by `\u0001`: `layerId \u0001 name \u0001
     * kind \u0001 lon \u0001 lat \u0001 featureId`. A `\u0001`-joined string
     * (not objects) keeps the boundary allocation-free on the native side and
     * parse-trivial on the Kotlin side. Empty array when nothing was placed or
     * nothing hits.
     *
     * `kind` is the feature's own kind, not its layer's first one. `featureId` is the
     * archive's stable id, or `0` for a feature that has none.
     */
    external fun pickLabels(
        handle: Long,
        x0Dp: Float,
        y0Dp: Float,
        x1Dp: Float,
        y1Dp: Float,
    ): Array<String>

    /**
     * Destroy the renderer, wait for the GPU to go idle, and release the window.
     *
     * Must be called before the [Surface] is released, and exactly once per successful
     * [create].
     */
    external fun destroy(handle: Long)
}
