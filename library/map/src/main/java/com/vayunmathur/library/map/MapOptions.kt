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
 * Attribution is **not** one of them any more, despite [isAttributionEnabled] still being
 * here: `VectorMap` renders it unconditionally, because the OpenStreetMap data's ODbL and
 * the Protomaps schema both require visible credit, and that is a licence condition rather
 * than a host preference. [AllDisabled] therefore cannot hide it — `weather` passes
 * [AllDisabled] and still gets attribution, which is the intended behaviour.
 *
 * The flag is kept because it is part of the options object's shape; it currently governs
 * nothing else, since attribution is the only ornament that was ever implemented.
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
