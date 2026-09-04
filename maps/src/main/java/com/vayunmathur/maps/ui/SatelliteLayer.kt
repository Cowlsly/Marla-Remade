package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable

/**
 * Satellite / aerial imagery layer (P6), ported conceptually from Vela's
 * `SatelliteLayer`.
 *
 * ⚠️ GATED — NO TILE SOURCE YET: [TILE_URL] is intentionally blank. MA's basemap
 * is vector (the streamed PMTiles archive, now rendered by our own Vulkan renderer);
 * there is no hosted satellite/aerial raster tileset, and the Vela clone that
 * referenced Google's imagery was removed after research (see
 * `maps/VELA_PORT_PLAN.md`, Decision D11). While unavailable the layers sheet
 * hides the satellite toggle and this composable renders nothing.
 *
 * To activate: host an XYZ raster tileset, set [TILE_URL] to it, and draw it here
 * behind the [available] guard (e.g. via library:map's `ImageOverlay` once a tile
 * fetching path exists — the renderer has no raster-layer API today).
 */
object SatelliteSource {
    const val TILE_URL: String = ""

    val available: Boolean get() = TILE_URL.isNotBlank()
}

/**
 * Mount the satellite raster overlay when [enabled] and a tile source exists.
 * No-op (renders nothing) while [SatelliteSource] is unavailable, so it is
 * always safe to include in the layer tree.
 */
@Composable
fun SatelliteLayer(enabled: Boolean) {
    if (!enabled || !SatelliteSource.available) return
    // Intentionally empty until a raster tileset is hosted (see SatelliteSource).
}
