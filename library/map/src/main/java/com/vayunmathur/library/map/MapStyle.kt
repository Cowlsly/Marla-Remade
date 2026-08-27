package com.vayunmathur.library.map

/**
 * Which basemap to draw.
 *
 * Replaces `TileSource`, which named a CARTO raster URL template. There is no URL here
 * because there is no third-party tile source any more: every app renders the self-hosted
 * `data.vayunmathur.com/v4.pmtiles` archive, and the only choice left is how to paint it.
 *
 * Light and dark are a separate axis — see `VectorMap`'s `darkBasemap`, which follows the
 * system theme — so each of these has both.
 */
enum class MapStyle {
    /**
     * The full-contrast basemap. What most apps want.
     *
     * Replaces `TileSource.CartoVoyager`.
     */
    Standard,

    /**
     * The same map, receded, for hosts that draw their own data on top of it.
     *
     * Replaces `TileSource.CartoPositron`, and exists for the same reason it did: `weather`
     * draws a colour-ramp overlay over the basemap, and a full-contrast map underneath
     * fights it for attention. Every colour is blended toward the background, so roads
     * still read as roads but nothing competes with the overlay.
     */
    Muted,
}
