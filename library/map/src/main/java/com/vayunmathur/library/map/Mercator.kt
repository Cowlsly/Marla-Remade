package com.vayunmathur.library.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/** A point in Web-Mercator world pixels (logical, 256 px/tile, known-good grid). */
internal data class WorldPx(val x: Double, val y: Double)

/**
 * Standard Web Mercator with a 256-logical-px tile grid, matching the Rust
 * renderer's `TILE_SIZE`. All values are in density-independent ("logical")
 * pixels so callers can work in `Dp` and only convert to device pixels when
 * drawing. The projection is internally consistent with the tile rendering
 * (both use this object), so overlays pin exactly to the basemap regardless
 * of device density.
 *
 * MapLibre parity (ground scale at the same zoom float) comes from a +1 zoom
 * offset applied at the camera boundary, NOT from a 512 tile: a 512 TILE_SIZE
 * was tried and reverted (task 1, magenta verdict — label quads invisible,
 * roads cut at seams) while 256 renders both clean.
 */
internal object Mercator {
    const val TILE_SIZE = 256.0

    /** Total map width/height in logical px at [zoom]. */
    fun worldSize(zoom: Double): Double = TILE_SIZE * 2.0.pow(zoom)

    /** Project lon/lat (degrees) to world px at [zoom]. */
    fun project(longitude: Double, latitude: Double, zoom: Double): WorldPx {
        val ws = worldSize(zoom)
        val lat = latitude.coerceIn(-85.05112878, 85.05112878)
        val x = (longitude + 180.0) / 360.0 * ws
        val sinLat = sin(lat * PI / 180.0)
        val y = (0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * PI)) * ws
        return WorldPx(x, y)
    }

    /** Inverse of [project]: world px at [zoom] back to lon/lat degrees. */
    fun unproject(x: Double, y: Double, zoom: Double): GeoPoint {
        val ws = worldSize(zoom)
        val longitude = x / ws * 360.0 - 180.0
        val n = PI - 2 * PI * y / ws
        val latitude = atan(sinh(n)) * 180.0 / PI
        return GeoPoint(longitude, latitude)
    }
}
