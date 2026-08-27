package com.vayunmathur.library.map

import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The viewport contract between [VectorMap] and [CameraState].
 *
 * These exist because deleting `RasterMap` deleted the only caller of
 * [CameraState.setViewport] with it, and nothing failed: the module compiled, every unit
 * test passed, all five apps built, and the map was silently broken on device. Nothing
 * asserted that *something* measures the viewport.
 *
 * The failure was invisible in exactly the way that matters — [CameraState.viewportDp]
 * stays null, so the renderer skips every frame *and* [CameraState.projection] returns
 * null, which means every pin, marker and photo cluster in all five apps stops
 * positioning. One missing modifier, five broken apps, zero failing tests.
 */
class CameraViewportTest {

    @Test
    fun a_camera_has_no_projection_until_its_viewport_is_measured() {
        // The pre-measurement state, and the reason the renderer must tolerate it rather
        // than assuming a size.
        val camera = CameraState()
        assertNull(camera.viewportDp, "a fresh camera has not been measured")
        assertNull(camera.projection, "and so cannot project")
    }

    @Test
    fun measuring_the_viewport_enables_projection() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 14.0))
        camera.setViewport(Size(411f, 891f))
        assertEquals(Size(411f, 891f), camera.viewportDp)
        val projection = assertNotNull(camera.projection, "projection must exist once measured")
        // The camera's target lands on the viewport centre.
        val centre = projection.screenLocationFromPosition(camera.position.target)
        assertTrue(abs(centre.x.value - 411f / 2f) < 0.01f, "x was ${centre.x}")
        assertTrue(abs(centre.y.value - 891f / 2f) < 0.01f, "y was ${centre.y}")
    }

    @Test
    fun measuring_the_viewport_enforces_the_fill_zoom() {
        // Otherwise zooming all the way out leaves blank margins around a world smaller
        // than the viewport.
        val camera = CameraState(CameraPosition(GeoPoint(0.0, 0.0), 0.0))
        camera.setViewport(Size(1024f, 768f))
        assertTrue(
            camera.position.zoom >= 2.0,
            "1024 dp needs at least z2 to fill; got ${camera.position.zoom}",
        )
    }

    @Test
    fun a_zoom_above_the_fill_floor_is_left_alone() {
        val camera = CameraState(CameraPosition(GeoPoint(0.0, 0.0), 14.0))
        camera.setViewport(Size(411f, 891f))
        assertEquals(14.0, camera.position.zoom, 1e-9)
    }

    @Test
    fun a_degenerate_viewport_does_not_force_a_zoom_or_crash() {
        // Compose reports 0x0 for a composable that has been laid out with no space.
        val camera = CameraState(CameraPosition(GeoPoint(0.0, 0.0), 5.0))
        camera.setViewport(Size(0f, 0f))
        assertEquals(5.0, camera.position.zoom, 1e-9)
    }

    private fun abs(v: Float) = if (v < 0) -v else v
}
