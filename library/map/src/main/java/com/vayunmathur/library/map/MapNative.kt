package com.vayunmathur.library.map

import android.view.Surface

/**
 * The JNI surface of the Vulkan renderer in `library/map/src/main/rust`.
 *
 * Deliberately five methods and nothing per-feature: Kotlin creates and destroys the
 * renderer for a [Surface], resizes it, reports connectivity, and hands it **one
 * camera snapshot per frame**. Tile selection, fetching, decode, tessellation and
 * drawing all happen on the native side, so the boundary is crossed a handful of times
 * a frame rather than thousands.
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
     */
    external fun render(
        handle: Long,
        centerLon: Float,
        centerLat: Float,
        zoom: Float,
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
     * Tell the renderer whether the device is online. When offline it serves stale cached
     * ranges instead of attempting a request, so a previously-viewed area keeps drawing.
     */
    external fun setOnline(handle: Long, online: Boolean)

    /**
     * Pick placed labels (task 17): the last frame's placed symbol labels
     * whose screen boxes intersect the query box, in placement order.
     *
     * All four box edges are Dp from the viewport top-left. Returns one
     * `String` per hit, fields joined by `\u0001`: `layerId \u0001 name \u0001
     * kind \u0001 lon \u0001 lat`. A `\u0001`-joined string (not objects)
     * keeps the boundary allocation-free on the native side and parse-trivial
     * on the Kotlin side. Empty array when nothing was placed or nothing hits.
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
