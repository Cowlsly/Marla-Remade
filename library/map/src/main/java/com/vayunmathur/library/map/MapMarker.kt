package com.vayunmathur.library.map

/**
 * A pin the renderer draws inside its own frame, glued to a ground position.
 *
 * The sprite counterpart of [UserPuck]: moved into the renderer so it pans and tilts in
 * lock-step with the basemap instead of trailing it by a frame the way a Compose overlay does.
 * A host passes a list to [VectorMap]'s `markers`; a tap resolves back to one through
 * [Projection.pickMarker], which returns the [id] set here.
 *
 * @param id the host's own stable id for this pin, echoed back verbatim by
 *   [Projection.pickMarker] so the host maps a tap to its feature without matching on position.
 *   `0` is reserved by the pick path to mean "nothing", so avoid it for a real pin.
 * @param position the ground point the pin sits on.
 * @param icon which atlas icon to draw — one of [MarkerIcon].
 */
data class MapMarker(
    val id: Long,
    val position: GeoPoint,
    val icon: Int,
)

/**
 * The marker icon ids, mirroring the renderer's `crate::marker::icon` table. Passed as an int
 * across the JNI boundary (see [MapNative.setMarkers]); the renderer resolves each to a sprite in
 * the shared atlas.
 *
 * The pin ids are the app's own; the vehicle ids are reserved for the transit-vehicle overlay
 * (WS-F) so a mode resolves to a sprite through the same table. An id the renderer does not know
 * simply draws nothing, so adding one here without the native side is a no-op rather than a crash.
 */
object MarkerIcon {
    const val PARKING = 0
    const val TRANSIT_STOP = 1
    const val SEARCH = 2
    const val SAVED = 3
    const val FAMILY = 4

    const val VEHICLE_BUS = 5
    const val VEHICLE_TRAM = 6
    const val VEHICLE_TRAIN = 7
    const val VEHICLE_FERRY = 8
}
