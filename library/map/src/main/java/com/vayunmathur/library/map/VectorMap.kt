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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.DpRect
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
 * Fills, lines and casings, in light and dark, with pan, pinch, double-tap and quick zoom,
 * plus optional POI and transit layers and a native pick for tile-baked place labels. The
 * style is a ~14-layer stand-in rather than an authored one.
 *
 * If Vulkan fails to initialise the map shows its background colour rather than crashing,
 * and everything that failed is in logcat under `MapRenderer`. Hosts that want to say so can
 * pass [fallback]; `:library:ui` has a themed message for it. Validation layers are on in
 * debug builds.
 *
 * @param style [MapStyle.Standard] or [MapStyle.Muted] — muted for hosts drawing their own
 *   data on top, which is what `weather` needs.
 * @param darkBasemap which palette to paint. Defaults to the system theme. The dark colours
 *   are `maps`' own contrast-checked `BasemapPalette`, so the apps agree with each other.
 *   Switching is free: only a push constant changes.
 * @param imageOverlay a georeferenced translucent image drawn over the basemap.
 * @param userPuck the user's own location, drawn inside the renderer's frame so it stays
 *   glued to the ground while the map moves. Defaults to drawing nothing; see [UserPuck]
 *   for why this is not a [MapMarker].
 * @param regionMask dims everything outside the administrative region it names, for showing
 *   which city or country a details sheet is about. Drawn inside the renderer's frame for the
 *   same reason as [userPuck]: an overlay composed on top would lag the map by a frame while
 *   panning. `null` draws no mask.
 * @param onFrame called after each presented frame.
 *
 *   Kept for `library/map/src/androidTest/.../BasemapScreenshotTest.kt`, which counts frames
 *   to prove the renderer presented anything at all — the only way to observe that, since
 *   Vulkan needs a real GPU. It used to *also* fire from a `LaunchedEffect` on the camera,
 *   so that `photos` could re-project overlays on a pan that drew no new tiles; [MapMarker]
 *   makes that unnecessary and nothing else wanted the second signal, so this now means
 *   what its name says and nothing more.
 * @param fallback drawn over the surface when the renderer could not be brought up, so a
 *   failure is not a silent flat rectangle. Defaults to drawing nothing, which is the old
 *   behaviour. `:library:ui` has a themed, translated message for this.
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
    userPuck: UserPuck? = null,
    regionMask: RegionMask? = null,
    onMapClick: (GeoPoint) -> Unit = {},
    /**
     * Tap with the screen point attached (see [MapClick]): what
     * `MapFeaturePicker`-style hit-testing needs. Null (default) means
     * taps report geo only, exactly as before - existing call sites are
     * unaffected.
     *
     * When [LayerOptions.poi] is on, [MapClick.poi] carries the topmost drawn POI under
     * the finger, so a host can open a place sheet for it and skip whatever it does with a
     * tap on empty map. The tap point is inflated by a touch slop first — a 19 Dp icon is
     * smaller than a fingertip.
     */
    onMapClickWithScreen: ((MapClick) -> Unit)? = null,
    onFrame: () -> Unit = {},
    archivePath: String? = null,
    fallback: @Composable (MapRenderState.Unavailable) -> Unit = {},
    content: @Composable MapScope.() -> Unit = {},
) {
    val density = LocalDensity.current.density

    // Read through `rememberUpdatedState` so the lambda handed to `mapGestures` keeps a
    // stable identity: its `pointerInput` is keyed on the camera and gesture options, not on
    // the callbacks, so a fresh lambda per recomposition would sit unused behind a detector
    // that never restarted.
    val latestScreenClick by rememberUpdatedState(onMapClickWithScreen)
    val poiEnabled = options.layerOptions.poi
    val latestPoiEnabled by rememberUpdatedState(poiEnabled)
    val screenClick = remember(cameraState) {
        { click: MapClick ->
            val handler = latestScreenClick
            if (handler != null) {
                // Queried only when POI is drawn: with the layer off there is nothing placed
                // to hit, and the query would be a pick over the place labels for nothing.
                val poi = if (latestPoiEnabled) {
                    val point = click.screen
                    val box = DpRect(
                        left = point.x - POI_TOUCH_SLOP,
                        top = point.y - POI_TOUCH_SLOP,
                        right = point.x + POI_TOUCH_SLOP,
                        bottom = point.y + POI_TOUCH_SLOP,
                    )
                    cameraState.projection?.queryRenderedLabels(box, POI_LAYER_IDS)?.firstOrNull()
                } else {
                    null
                }
                handler(click.copy(poi = poi))
            }
        }
    }

    // Remembered and @Stable so the content lambda gets the same receiver across
    // recompositions. A fresh receiver every time would make large content subtrees —
    // `maps`' MapLayers is one — permanently unskippable.
    val mapScope = remember(cameraState) { MapScope(cameraState) }

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
            .mapGestures(cameraState, options.gestureOptions, zoomRange, density, onMapClick, screenClick),
    ) {
        VulkanMapSurface(
            cameraState = cameraState,
            darkBasemap = darkBasemap,
            muted = style == MapStyle.Muted,
            layerOptions = options.layerOptions,
            archivePath = archivePath,
            userPuck = userPuck,
            regionMask = regionMask,
            modifier = Modifier.fillMaxSize(),
            onFrame = onFrame,
            fallback = fallback,
        )

        if (imageOverlay != null) {
            GeoreferencedOverlay(imageOverlay, cameraState)
        }

        mapScope.content()
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
 * How far around a tap to look for a POI.
 *
 * A POI icon is 19 Dp and a fingertip is nearer 40, so an exact-point test would make icons
 * feel unhittable. Half the Material minimum touch target, applied as a radius, gives a
 * 24 Dp box centred on the finger.
 */
private val POI_TOUCH_SLOP = 12.dp

/**
 * The style's six POI symbol layers, which is what [MapClick.poi] reports hits from. Place
 * labels (`places-*`) are deliberately excluded: a country or city label is not a point of
 * interest, and a host that wants those can query for them directly.
 */
private val POI_LAYER_IDS = setOf(
    "poi-outdoor",
    "poi-transport",
    "poi-civic",
    "poi-shop",
    "poi-food",
    "poi-culture",
)

/**
 * Shown before any tile has loaded. Matches the renderer's own backdrop, so the surface
 * appearing does not flash a different colour.
 */
private const val LIGHT_BACKGROUND = 0xFFE9E7E2

/** `BasemapPalette.Fill.Background`, so this and `maps` agree in the dark. */
private const val DARK_BACKGROUND = 0xFF1B1D22

/**
 * The attribution text the overlay used to draw: "© OpenStreetMap contributors · Protomaps".
 *
 * REMOVED from the map by task 51 (#9): the overlay no longer exists. The string stays as
 * the canonical credit for any host About/Legal screen to reuse — the tiles are still
 * OpenStreetMap data under ODbL tiled to the Protomaps schema, and the ODbL requires the
 * credit *somewhere* visible. No app currently shows it elsewhere (checked: maps,
 * findfamily, weather, photos, communicate have no OSM/ODbL/attribution screen or string),
 * so until a host adds one this product ships WITHOUT the required attribution — the user
 * must place it. CARTO is deliberately not credited: we no longer use their CDN.
 */
internal const val ATTRIBUTION = "© OpenStreetMap contributors · Protomaps"
