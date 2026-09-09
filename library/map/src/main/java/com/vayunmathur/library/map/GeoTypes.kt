package com.vayunmathur.library.map

/**
 * Pure-Kotlin geo point. Longitude first, latitude second (same order as GeoJSON).
 *
 * This is the coordinate type for :library:map and all of its consumers.
 */
data class GeoPoint(
    val longitude: Double,
    val latitude: Double,
)

/** Pure-Kotlin bounding box, in west/south/east/north order. */
data class GeoBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)
