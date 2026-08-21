package com.vayunmathur.maps.util

import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * The world, used when the camera cannot say what is on screen.
 *
 * Latitude stops at ±85° rather than ±90° because that is where the web-mercator projection
 * the basemap uses runs out; the poles are not addressable in it.
 */
private val World = BoundingBox(west = -180.0, south = -85.0, east = 180.0, north = 85.0)

/**
 * The visible map bounds, falling back to the whole world.
 *
 * `camera.projection` is null until the style finishes loading, which is a state a user can
 * reach — tapping search during the first frames after launch. Every caller wants the same
 * fallback for the same reason (a search biased to the world still works; a search biased to
 * nothing does not), and each was spelling out four `?:` defaults, so a typo in any one of
 * them would have silently biased results to the wrong hemisphere.
 */
fun CameraState.visibleBoundsOrWorld(): BoundingBox =
    projection?.queryVisibleBoundingBox() ?: World
