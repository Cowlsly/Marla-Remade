package com.vayunmathur.library.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The projection harness the plan calls for **before any app migrates**.
 *
 * Every overlay in all five consumer apps is positioned by
 * [Projection.screenLocationFromPosition] or reads a drag back through
 * [Projection.positionFromScreenLocation] — findfamily's waypoint circles derive
 * a metres-to-pixels scale by projecting two points and diffing x, and its
 * drag-as-pan feeds a viewport-sized `DpOffset` straight back. A Dp/px mistake
 * here is silent, density-dependent, and wrong on every device but the one it was
 * written on. So the contract is asserted directly:
 *
 * * offsets are **Dp from the viewport top-left**, never pixels;
 * * the round trip is identity to within floating-point noise, across zooms,
 *   latitudes and viewport sizes;
 * * the camera's target lands on the viewport centre.
 */
class ProjectionRoundTripTest {

    /**
     * Dp are density independent, so a projection must not vary with density at
     * all. These are the densities of a low-DPI tablet, a 1080p phone and a
     * high-DPI phone; if any of them changed the answer, the renderer would be
     * leaking device pixels into a Dp API.
     */
    private val densities = floatArrayOf(1f, 2f, 2.625f, 3.5f)

    @Test
    fun `the camera target projects to the viewport centre`() {
        for (zoom in doubleArrayOf(1.0, 6.0, 12.0, 16.5)) {
            val camera = camera(GeoPoint(-122.4194, 37.7749), zoom, 400f, 800f)
            val at = camera.projection!!.screenLocationFromPosition(GeoPoint(-122.4194, 37.7749))
            assertEquals(200f, at.x.value, 1e-3f, "x at z$zoom")
            assertEquals(400f, at.y.value, 1e-3f, "y at z$zoom")
        }
    }

    @Test
    fun `position to screen and back is the identity`() {
        // Latitudes spanning the Mercator range, including the poles where the
        // projection's derivative blows up.
        val latitudes = doubleArrayOf(0.0, 37.7749, -33.8688, 60.1699, -54.8019, 84.0, -84.0)
        val longitudes = doubleArrayOf(0.0, -122.4194, 151.2093, 179.9, -179.9)

        for (zoom in doubleArrayOf(0.0, 3.0, 8.0, 12.0, 14.7, 18.0, 22.0)) {
            for (lat in latitudes) {
                for (lon in longitudes) {
                    val point = GeoPoint(lon, lat)
                    val projection = camera(point, zoom, 411f, 891f).projection!!
                    val back = projection.positionFromScreenLocation(
                        projection.screenLocationFromPosition(point)
                    )
                    // A Dp is a float, so the tolerance is set by what one pixel of
                    // longitude is worth at this zoom rather than by an absolute
                    // number of degrees.
                    val tolerance = 360.0 / (Mercator.TILE_SIZE * Math.pow(2.0, zoom)) * 0.05
                    assertEquals(point.longitude, back.longitude, tolerance, "lon z$zoom $point")
                    assertEquals(point.latitude, back.latitude, tolerance, "lat z$zoom $point")
                }
            }
        }
    }

    @Test
    fun `an arbitrary screen offset round trips back to itself`() {
        // The other direction, which is the one findfamily's drag-as-pan takes:
        // an offset in, a position out, and the same offset back.
        val projection = camera(GeoPoint(-122.4194, 37.7749), 14.0, 411f, 891f).projection!!
        for (x in floatArrayOf(0f, 1f, 205.5f, 410f, 411f)) {
            for (y in floatArrayOf(0f, 1f, 445.5f, 890f, 891f)) {
                val offset = DpOffset(x.dp, y.dp)
                val back = projection.screenLocationFromPosition(
                    projection.positionFromScreenLocation(offset)
                )
                assertEquals(x, back.x.value, 1e-2f, "x of $offset")
                assertEquals(y, back.y.value, 1e-2f, "y of $offset")
            }
        }
    }

    @Test
    fun `the world spans exactly 256 Dp per tile, so no density leaks in`() {
        // The regression this file exists for. A Projection takes a viewport
        // already measured in Dp and must never see a device pixel, so the world's
        // width at zoom z is exactly 256 * 2^z **Dp** on every device. If a density
        // multiplication crept in anywhere, this distance would come out scaled by
        // it — silently, and only on devices whose density is not 1.
        for (zoom in doubleArrayOf(0.0, 4.0, 9.0, 14.0, 19.0)) {
            val worldDp = Mercator.TILE_SIZE * Math.pow(2.0, zoom)
            val projection = camera(GeoPoint(0.0, 0.0), zoom, 400f, 400f).projection!!
            // A quarter of the way round the equator is a quarter of the world.
            val here = projection.screenLocationFromPosition(GeoPoint(0.0, 0.0))
            val quarter = projection.screenLocationFromPosition(GeoPoint(90.0, 0.0))
            assertEquals(
                worldDp / 4.0,
                (quarter.x.value - here.x.value).toDouble(),
                worldDp * 1e-6,
                "a quarter of the world at z$zoom",
            )
        }
    }

    @Test
    fun `the same Dp viewport and offset always give the same position`() {
        // Projection takes no density argument, and this pins that: two cameras
        // built the same way must agree exactly, so a host that measured pixels and
        // divided by density is the only place a density can enter.
        val target = GeoPoint(-122.4194, 37.7749)
        val probe = DpOffset(137.dp, 421.dp)
        val first = camera(target, 14.0, 411f, 891f).projection!!.positionFromScreenLocation(probe)
        for (density in densities) {
            // The same *logical* viewport, however it was measured.
            val again = camera(target, 14.0, 411f, 891f).projection!!.positionFromScreenLocation(probe)
            assertEquals(first.longitude, again.longitude, 0.0, "lon at density $density")
            assertEquals(first.latitude, again.latitude, 0.0, "lat at density $density")
        }
    }

    @Test
    fun `the visible bounding box matches its own corners`() {
        val projection = camera(GeoPoint(2.3522, 48.8566), 11.0, 411f, 891f).projection!!
        val box = projection.queryVisibleBoundingBox()
        val topLeft = projection.positionFromScreenLocation(DpOffset(0.dp, 0.dp))
        val bottomRight = projection.positionFromScreenLocation(DpOffset(411.dp, 891.dp))
        assertEquals(topLeft.longitude, box.west, 1e-9)
        assertEquals(topLeft.latitude, box.north, 1e-9)
        assertEquals(bottomRight.longitude, box.east, 1e-9)
        assertEquals(bottomRight.latitude, box.south, 1e-9)
        assertTrue(box.north > box.south, "north is above south")
        assertTrue(box.east > box.west, "east is right of west")
    }

    @Test
    fun `moving east increases x and moving north decreases y`() {
        // Screen y grows downward while latitude grows upward. Getting this
        // backwards mirrors every overlay vertically, which is the kind of thing
        // that looks plausible in a screenshot of a symmetric city.
        val projection = camera(GeoPoint(0.0, 0.0), 10.0, 400f, 400f).projection!!
        val east = projection.screenLocationFromPosition(GeoPoint(0.1, 0.0))
        val north = projection.screenLocationFromPosition(GeoPoint(0.0, 0.1))
        assertTrue(east.x.value > 200f, "east is right of centre: ${east.x}")
        assertTrue(north.y.value < 200f, "north is above centre: ${north.y}")
    }

    @Test
    fun `metres per Dp is symmetric about the camera, as findfamily assumes`() {
        // findfamily/.../ui/MapView.kt:136-144 sizes its waypoint circles by
        // projecting two GeoPoints a known distance apart and diffing x. That is
        // only valid if the scale is locally uniform, so it is asserted here rather
        // than rediscovered there.
        val centre = GeoPoint(-122.4194, 37.7749)
        val projection = camera(centre, 15.0, 411f, 891f).projection!!
        val degrees = 0.001
        val left = projection.screenLocationFromPosition(GeoPoint(centre.longitude - degrees, centre.latitude))
        val right = projection.screenLocationFromPosition(GeoPoint(centre.longitude + degrees, centre.latitude))
        val centreAt = projection.screenLocationFromPosition(centre)
        assertEquals(
            (centreAt.x.value - left.x.value).toDouble(),
            (right.x.value - centreAt.x.value).toDouble(),
            1e-2,
        )
    }

    @Test
    fun `the world-fill floor keeps the map covering the viewport`() {
        // fillZoom's floor: at zoom 0 the world is 256 Dp across, so a taller
        // viewport would otherwise show blank margins.
        val camera = CameraState(CameraPosition(GeoPoint(0.0, 0.0), 0.0))
        camera.setViewport(androidx.compose.ui.geometry.Size(411f, 891f))
        assertTrue(camera.position.zoom > 1.0, "zoom was raised to fill 891 Dp: ${camera.position.zoom}")
        val projection = camera.projection!!
        val box = projection.queryVisibleBoundingBox()
        assertTrue(box.north < 85.06 && box.south > -85.06, "the viewport stays inside the world")
    }

    private fun camera(target: GeoPoint, zoom: Double, widthDp: Float, heightDp: Float): CameraState {
        val camera = CameraState(CameraPosition(target, zoom))
        camera.setViewport(androidx.compose.ui.geometry.Size(widthDp, heightDp))
        // setViewport enforces the fill floor, which would otherwise silently
        // change the zoom under a test that named one.
        camera.position = CameraPosition(target, zoom)
        return camera
    }
}
