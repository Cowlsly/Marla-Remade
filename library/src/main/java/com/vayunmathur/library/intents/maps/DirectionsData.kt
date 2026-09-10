package com.vayunmathur.library.intents.maps

import kotlinx.serialization.Serializable

/**
 * Shared wire types for maps' `DirectionsIntent`
 * [AssistantIntent][com.vayunmathur.library.util.AssistantIntent] — a co-signed caller (the
 * taxi app) asks maps to plan a route and gets back the polyline and the per-segment colouring
 * inputs it needs to draw the route inside its own `VectorMap` the way maps draws it.
 *
 * These are plain `@Serializable` doubles: [com.vayunmathur.library.map.GeoPoint] is not
 * serializable, so coordinates cross the process boundary as lat/lng pairs and the caller
 * rebuilds `GeoPoint`s on the far side.
 */
@Serializable
data class DirectionsRequest(
    val fromLat: Double,
    val fromLng: Double,
    val toLat: Double,
    val toLng: Double,
    /**
     * Travel mode, matching `RouteService.TravelMode` case-insensitively (`drive`, `transit`,
     * `walk`, `bicycle`). Anything unrecognised is planned as driving — the taxi caller only
     * ever asks for a driving route.
     */
    val mode: String,
)

/**
 * A planned route: the full [polyline] for framing, the coloured [segments] for drawing, and
 * the trip totals.
 *
 * An empty [polyline]/[segments] means no route could be planned (e.g. the pack cannot route
 * the pair) — the caller draws nothing rather than treating it as an error.
 */
@Serializable
data class DirectionsResult(
    val polyline: List<DirectionsLatLng>,
    val segments: List<DirectionsSegment>,
    val distanceMeters: Double,
    val durationSeconds: Double,
)

/** A single coordinate, lat/lng, the serializable stand-in for a `GeoPoint`. */
@Serializable
data class DirectionsLatLng(val lat: Double, val lng: Double)

/**
 * One coloured run of the route: its own [points] and the traffic [speedRatio] that decides
 * its colour. This mirrors the per-step split maps' `RouteOverlayBuilder` produces, so the
 * caller colours each run by the same red/amber/green congestion ramp maps uses
 * (`< 0.5` jam, `< 0.9` slow, else free).
 */
@Serializable
data class DirectionsSegment(
    val points: List<DirectionsLatLng>,
    val speedRatio: Double,
)
