package com.vayunmathur.library.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CameraPosition.bearing]'s contract with the Compose path.
 *
 * Bearing exists for Android Auto's heading-up navigation and is driven through
 * [SurfaceMapRenderer.camera], not through a [CameraState]. The risk it introduces is
 * therefore not that rotation is wrong — the renderer's own tests cover that — but that
 * adding a third field silently changes the phone: a `CameraPosition(target, zoom)` that
 * stopped compiling, or a gesture that rebuilds the position from scratch and resets a
 * bearing the host set.
 */
class CameraBearingTest {

    @Test
    fun a_camera_is_north_up_unless_it_is_told_otherwise() {
        assertEquals(0.0, CameraPosition().bearing)
        // The two-argument form every existing call site uses, still positional.
        assertEquals(0.0, CameraPosition(GeoPoint(-122.4194, 37.7749), 14.0).bearing)
    }

    @Test
    fun a_gesture_pans_and_zooms_without_straightening_the_map() {
        // `onGesture` used to build a fresh `CameraPosition(center, zoom)`, which would
        // drop the bearing on the first touch of a heading-up map.
        val camera = CameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 14.0, 135.0))
        camera.setViewport(Size(411f, 891f))
        camera.onGesture(
            centroidDp = Offset(205f, 445f),
            panDp = Offset(30f, -20f),
            zoomChange = 1.5f,
            minZoom = 0.0,
            maxZoom = 20.0,
            scrollEnabled = true,
            zoomEnabled = true,
        )
        assertEquals(135.0, camera.position.bearing, 1e-9)
        assertTrue(camera.position.zoom > 14.0, "the pinch still zoomed: ${camera.position.zoom}")
    }

    @Test
    fun a_quick_zoom_keeps_the_bearing_too() {
        val start = CameraPosition(GeoPoint(0.0, 0.0), 12.0, 42.0)
        val camera = CameraState(start)
        camera.setViewport(Size(411f, 891f))
        camera.onQuickZoom(
            from = start,
            anchorDp = Offset(205f, 445f),
            dragDp = 100f,
            minZoom = 0.0,
            maxZoom = 20.0,
        )
        assertEquals(42.0, camera.position.bearing, 1e-9)
    }
}
