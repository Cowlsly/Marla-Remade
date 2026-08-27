package com.vayunmathur.library.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * The vector map.
 *
 * Renders the self-hosted PMTiles archive at `data.vayunmathur.com/v4.pmtiles` — the same
 * one `maps` streams through MapLibre — with our own Vulkan renderer. No third-party tile
 * CDN and no API key, which is the whole point: CARTO's keyless raster tiles now come back
 * watermarked, and that broke the basemap in five apps (#615).
 *
 * The signature is deliberately close to the `RasterMap` it replaces, so migrating a
 * consumer is an import change and a `tileSource` → `style` rename.
 *
 * ## What is and is not finished
 *
 * Fills, lines and casings, in light and dark, with pan, pinch, double-tap and quick zoom.
 * There are deliberately **no labels** — text is Phase 7 and realistically 40–60% of the
 * total effort — and the style is a ~14-layer stand-in rather than an authored one.
 *
 * It has also **not been verified on hardware.** The whole CPU pipeline is unit-tested, but
 * no frame has been presented on a real GPU. If Vulkan fails to initialise the map shows
 * its background colour rather than crashing, and everything that failed is in logcat under
 * `MapRenderer`. Validation layers are on in debug builds.
 *
 * @param style [MapStyle.Standard] or [MapStyle.Muted] — muted for hosts drawing their own
 *   data on top, which is what `weather` needs.
 * @param darkBasemap which palette to paint. Defaults to the system theme. The dark colours
 *   are `maps`' own contrast-checked `BasemapPalette`, so the apps agree with each other.
 *   Switching is free: only a push constant changes.
 * @param imageOverlay a georeferenced translucent image drawn over the basemap.
 * @param onFrame called after each presented frame — a real per-frame hook, which is what
 *   `photos` needs and MapLibre cannot offer.
 */
@Composable
fun VectorMap(
    cameraState: CameraState,
    modifier: Modifier = Modifier,
    style: MapStyle = MapStyle.Standard,
    darkBasemap: Boolean = isSystemInDarkTheme(),
    zoomRange: ClosedFloatingPointRange<Float> = 0f..20f,
    options: MapOptions = MapOptions(),
    imageOverlay: ImageOverlay? = null,
    onMapClick: (GeoPoint) -> Unit = {},
    onFrame: () -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current.density

    // A camera move has to reach a consumer that reprojects overlays even on a frame that
    // drew nothing new: a pan with no new tiles still moves every pin.
    LaunchedEffect(cameraState.position, cameraState.viewportDp) {
        if (cameraState.viewportDp != null) onFrame()
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(if (darkBasemap) DARK_BACKGROUND else LIGHT_BACKGROUND))
            // Measure the viewport. Everything downstream depends on this: the renderer
            // skips any frame with no viewport, and `CameraState.projection` is null
            // without it — so every pin, marker and cluster the host positions would fail
            // too. It also enforces the minimum "fill" zoom so the world never leaves blank
            // margins.
            .onSizeChanged {
                cameraState.setViewport(Size(it.width / density, it.height / density))
            }
            .mapGestures(cameraState, options.gestureOptions, zoomRange, density, onMapClick),
    ) {
        VulkanMapSurface(
            cameraState = cameraState,
            darkBasemap = darkBasemap,
            muted = style == MapStyle.Muted,
            modifier = Modifier.fillMaxSize(),
            onFrame = onFrame,
        )

        if (imageOverlay != null) {
            GeoreferencedOverlay(imageOverlay, cameraState)
        }

        content()

        // Structurally non-disableable: the data is OpenStreetMap's under ODbL and the
        // schema is Protomaps', so this is a licence condition rather than a preference.
        // `MapOptions.kt:22-23` already asserts as much, and there is deliberately no
        // parameter to hide it — `OrnamentOptions.AllDisabled` cannot.
        MapAttribution(Modifier.align(Alignment.BottomStart))
    }
}

/**
 * A georeferenced image stretched into its bounds' screen rect.
 *
 * Drawn in Compose above the Vulkan surface rather than inside the renderer. The map is
 * north-up and axis-aligned, so this is one screen-aligned quad — exactly what the raster
 * renderer did with `drawImage`, and visually identical. Doing it on the GPU side would
 * mean a texture pipeline: a `VkImage`, a sampler, a descriptor set and a second set of
 * upload paths, for a single full-screen quad that Compose already composites.
 *
 * The tradeoff to know about: it is composited *over* everything the renderer draws, so
 * once labels exist (Phase 7) an overlay that should sit *under* them would have to move
 * into the renderer.
 */
@Composable
private fun GeoreferencedOverlay(overlay: ImageOverlay, cameraState: CameraState) {
    Canvas(Modifier.fillMaxSize()) {
        val projection = cameraState.projection ?: return@Canvas
        val northWest = projection.screenLocationFromPosition(
            GeoPoint(overlay.bounds.west, overlay.bounds.north)
        )
        val southEast = projection.screenLocationFromPosition(
            GeoPoint(overlay.bounds.east, overlay.bounds.south)
        )
        val left = northWest.x.toPx().roundToInt()
        val top = northWest.y.toPx().roundToInt()
        val right = southEast.x.toPx().roundToInt()
        val bottom = southEast.y.toPx().roundToInt()
        drawImage(
            image = overlay.bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(overlay.bitmap.width, overlay.bitmap.height),
            dstOffset = IntOffset(left, top),
            // A zero or negative extent is a degenerate bbox, which drawImage rejects.
            dstSize = IntSize((right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1)),
            alpha = overlay.opacity,
        )
    }
}

/**
 * Shown before any tile has loaded. Matches the renderer's own backdrop, so the surface
 * appearing does not flash a different colour.
 */
private const val LIGHT_BACKGROUND = 0xFFE9E7E2

/** `BasemapPalette.Fill.Background`, so this and `maps` agree in the dark. */
private const val DARK_BACKGROUND = 0xFF1B1D22

/** `© OpenStreetMap contributors` and the schema's author, as both licences require. */
@Composable
internal fun MapAttribution(modifier: Modifier = Modifier) {
    BasicText(
        text = ATTRIBUTION,
        modifier = modifier
            .padding(2.dp)
            .background(Color(0xB3FFFFFF))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        style = TextStyle(color = Color(0xFF444444), fontSize = 9.sp, fontFamily = FontFamily.SansSerif),
    )
}

/**
 * The tiles are OpenStreetMap data under ODbL, tiled to the Protomaps v4 schema.
 *
 * CARTO is deliberately gone: we no longer use their CDN, and still crediting them would be
 * as wrong as omitting OpenStreetMap.
 */
internal const val ATTRIBUTION = "© OpenStreetMap contributors · Protomaps"
