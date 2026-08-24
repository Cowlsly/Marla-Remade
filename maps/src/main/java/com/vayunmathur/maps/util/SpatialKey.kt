package com.vayunmathur.maps.util

/**
 * The 64-bit Morton (Z-order) key that `poi_index.bin` and `nodes.bin` are sorted by.
 *
 * This is a bit-for-bit cross-language contract with `latlng_to_spatial` in
 * `scripts/maps/osm_ingest/src/spatial.rs` (the writer) and `Graph::latlng_to_spatial` in
 * `maps/src/main/rust/src/graph.rs`. The device binary-searches files the Rust tools wrote, so
 * a one-bit disagreement does not fail — it silently returns the wrong records. `SpatialKeyTest`
 * pins this against vectors generated from the Rust implementation rather than from expectation.
 *
 * Keys are `u64` held in a `Long` for their bits only. Everything north of the equator has bit
 * 63 set, so a signed comparison sorts the whole northern hemisphere *below* the southern one:
 * every comparison has to go through [java.lang.Long.compareUnsigned].
 */
fun latLngToSpatial(lat: Double, lon: Double): Long {
    val x = (lon + 180.0) / 360.0
    val y = (lat + 90.0) / 180.0
    val ix = toU32(x * 4_294_967_295.0)
    val iy = toU32(y * 4_294_967_295.0)
    var res = 0L
    for (i in 0 until 32) {
        res = res or (((ix ushr i) and 1).toLong() shl (2 * i))
        res = res or (((iy ushr i) and 1).toLong() shl (2 * i + 1))
    }
    return res
}

/** [latLngToSpatial] for a stored `lat_e7`/`lon_e7` pair, mirroring `spatial_from_e7`. */
fun spatialFromE7(latE7: Int, lonE7: Int): Long =
    // `* 1e-7`, not `/ 1e7`. The two disagree in the last bit for roughly a third of e7 values
    // (they are different operations, and 1e-7 is not exactly representable). No sampled
    // disagreement actually reached the key, but this is a bit-for-bit contract: matching the
    // writer's arithmetic is cheaper than arguing about which differences survive truncation.
    latLngToSpatial(latE7 * 1e-7, lonE7 * 1e-7)

/**
 * The smallest Morton interval that is guaranteed to contain every point of the
 * `minLatE7..maxLatE7` × `minLonE7..maxLonE7` box, as `(first, last)` inclusive.
 *
 * Bit interleaving is monotone in each axis independently — the highest differing key bit
 * belongs to either x or y, and a 1 there forces that coordinate to be larger — so the box's two
 * extreme corners bound every point inside it. The interval is a *superset*: it also contains
 * points outside the box, which is why callers still test the exact coordinates.
 *
 * The interval is as wide as the box's Z-curve span, not as the box. A box straddling the
 * equator or the prime meridian flips a top-level bit and spans most of the key space, which is
 * Z-order's known discontinuity (pinned in `spatial.rs`'s
 * `morton_jumps_a_whole_quadrant_at_a_quadrant_boundary`). `poi_spatial.bin` replaces this with
 * an exact cell lookup; this is the bound available without a format change.
 */
fun spatialRangeForBbox(minLatE7: Int, maxLatE7: Int, minLonE7: Int, maxLonE7: Int): Pair<Long, Long> =
    spatialFromE7(minLatE7, minLonE7) to spatialFromE7(maxLatE7, maxLonE7)

/**
 * Rust's `f64 as u32`, which *saturates*: below zero clamps to 0, above `u32::MAX` clamps to
 * `u32::MAX`, NaN is 0.
 *
 * Kotlin's `Double.toInt()` saturates at `Int.MAX_VALUE` instead — half the range — which would
 * fold every longitude east of the prime meridian onto the same key. Going via `Long` keeps the
 * full range; `Double.toLong()` already saturates and already maps NaN to 0.
 */
private fun toU32(v: Double): Int = v.toLong().coerceIn(0L, 0xFFFF_FFFFL).toInt()
