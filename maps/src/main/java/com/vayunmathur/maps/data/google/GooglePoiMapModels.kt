package com.vayunmathur.maps.data.google

/**
 * List-oriented model for the custom Google POI overlay layer.
 *
 * [GooglePoiInfo] (the bottom-sheet enrichment) deliberately carries no id, no
 * lat/lng and no name — the caller already knew the place and supplied its
 * position. Map pins are the opposite: we discover many places from a viewport
 * scrape and must be able to draw each one and, on tap, re-select it. So this
 * fills that gap with the minimum a pin needs: a stable id, a name, a position,
 * plus category/rating for styling and [prominence] for ranking/limiting.
 */
data class GooglePoiPin(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val category: String? = null,
    val rating: Double? = null,
    val prominence: Double = 0.0,
)
