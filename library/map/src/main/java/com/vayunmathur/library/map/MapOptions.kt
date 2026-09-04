package com.vayunmathur.library.map

/**
 * Which pan/zoom gestures are enabled. Rotation and tilt are unsupported (the
 * map is always north-up), so there are no toggles for them.
 */
data class GestureOptions(
    val isScrollEnabled: Boolean = true,
    val isZoomEnabled: Boolean = true,
) {
    companion object {
        /** Pan + zoom enabled; rotation/tilt unsupported anyway. */
        val RotationLocked = GestureOptions(isScrollEnabled = true, isZoomEnabled = true)

        /** All gestures disabled (static map). */
        val AllDisabled = GestureOptions(isScrollEnabled = false, isZoomEnabled = false)
    }
}

/**
 * Map ornaments.
 *
 * Attribution used to live here as an ornament, but task 51 (#9) removed the overlay from
 * `VectorMap` outright: the ODbL credit the map drew belongs on a host About/Legal screen
 * instead (see `ATTRIBUTION` in `VectorMap.kt` — no app shows it elsewhere yet, so the user
 * must place it). The flag is kept because it is part of the options object's shape; it
 * currently governs nothing, since attribution was the only ornament ever implemented.
 */
data class OrnamentOptions(
    val isAttributionEnabled: Boolean = true,
) {
    companion object {
        val AllDisabled = OrnamentOptions(isAttributionEnabled = false)
    }
}

data class MapOptions(
    val gestureOptions: GestureOptions = GestureOptions(),
    val ornamentOptions: OrnamentOptions = OrnamentOptions(),
)
