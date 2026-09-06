package com.vayunmathur.library.map

/**
 * The user's own location, drawn by the renderer rather than by the host.
 *
 * Pass one to [VectorMap] and the puck appears; pass null and nothing is drawn. The host
 * needs no location code and no location permission of its own — `photos` and
 * `fooddelivery` hold none at all, which is exactly the case this serves: whoever already
 * has a fix hands the point in, and the library draws it.
 *
 * It is drawn **inside the renderer's frame**, from the same camera value as the basemap
 * under it, which is the whole reason it is not a [MapMarker]. Compose overlays are
 * produced by a second, unsynchronised pass over the same camera, so during a pan they
 * land on glass a frame or two behind the tiles and visibly drag (see [MapMarker]'s own
 * note). The puck is the one overlay with no hit-testing, so it could move without the
 * pin-picking machinery moving with it.
 *
 * The tradeoff that comes with that: it is now under *all* Compose content, so a pin the
 * host draws on the user's position will cover it. That is how Google Maps behaves.
 *
 * @param bearing degrees clockwise from north, or null when there is no heading yet.
 *   Null draws the dot without its bearing cone, which is a different thing from a
 *   heading of zero — passing `0f` for "unknown" points the cone spuriously north.
 */
data class UserPuck(
    val position: GeoPoint,
    val bearing: Float? = null,
)
