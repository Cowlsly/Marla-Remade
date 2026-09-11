package com.vayunmathur.maps.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scale bar previously overstated ground distance by a factor of six, which was two
 * independent errors multiplied together:
 *
 *  - it used 156543.03392, the z0 ground resolution for a **256**-px tile, while this stack is
 *    a 512-dp tile grid everywhere else (`Mercator.TILE_SIZE`, the Rust camera's `TILE_SIZE`) —
 *    a factor of 2; and
 *  - it then treated that per-**dp** figure as if it were per-**device pixel**, measuring the
 *    bar budget with `96.dp.toPx()` and laying the bar out in device px — a factor of the screen
 *    density, 3.0 on the device this was caught on.
 *
 * Both errors were in the same direction and the bar was internally self-consistent (label and
 * width derived from the same wrong number), so it looked plausible on screen and was only
 * wrong against the map underneath it. `cos(latitude)` was present and correct throughout.
 *
 * These assertions pin the ground truth independently of the implementation's algebra: the
 * world is 40075016.686 m around and 512 dp wide at z0, and latitude shrinks it by `cos φ`.
 */
class MapScaleBarTest {

    private fun assertClose(expected: Double, actual: Double, relativeTolerance: Double = 1e-9) {
        assertTrue(
            abs(actual - expected) <= abs(expected) * relativeTolerance,
            "expected $actual to be within ${relativeTolerance * 100}% of $expected",
        )
    }

    @Test
    fun `the world is 512 dp wide at zoom zero`() {
        assertClose(40075016.686, metersPerDp(zoom = 0.0, latitude = 0.0) * 512.0)
    }

    @Test
    fun `ground resolution halves with every zoom level`() {
        val z10 = metersPerDp(zoom = 10.0, latitude = 51.5)
        assertClose(z10 / 2.0, metersPerDp(zoom = 11.0, latitude = 51.5))
        assertClose(z10 / 1024.0, metersPerDp(zoom = 20.0, latitude = 51.5))
    }

    @Test
    fun `latitude shrinks ground resolution by cos of the parallel`() {
        val equator = metersPerDp(zoom = 14.0, latitude = 0.0)
        // cos 60 is exactly 0.5, so this catches a missing, doubled or squared cos term.
        assertClose(equator * 0.5, metersPerDp(zoom = 14.0, latitude = 60.0), 1e-6)
        assertClose(equator, metersPerDp(zoom = 14.0, latitude = -0.0))
        // Symmetric about the equator.
        assertClose(
            metersPerDp(zoom = 14.0, latitude = 37.58),
            metersPerDp(zoom = 14.0, latitude = -37.58),
        )
    }

    @Test
    fun `ground resolution matches the field-calibrated Broadway screenshot`() {
        // analysis/device_lanes2.png: z 17.8, lat 37.58, calibrated at 0.272 m per dp from the
        // lane-divider pitch. The old code reported 0.544 (x2) and laid it out against device
        // pixels (x3), for 118 px labelled "200 ft" where the ground truth was ~15 m.
        assertClose(0.27181, metersPerDp(zoom = 17.8, latitude = 37.58), 0.01)
    }

    @Test
    fun `the labelled distance is a nice round number that fits the bar`() {
        val (metres, metresLabel) = scaleBar(maxMeters = 26.1, imperial = false)
        assertEquals("20 m", metresLabel)
        assertClose(20.0, metres)

        val (kilometres, kilometresLabel) = scaleBar(maxMeters = 4900.0, imperial = false)
        assertEquals("2 km", kilometresLabel)
        assertClose(2000.0, kilometres)

        val (feet, feetLabel) = scaleBar(maxMeters = 26.1, imperial = true)
        assertEquals("50 ft", feetLabel)
        assertClose(50.0 / 3.28084, feet)

        val (miles, milesLabel) = scaleBar(maxMeters = 4900.0, imperial = true)
        assertEquals("2 mi", milesLabel)
        assertClose(2.0 * 1609.34, miles)
    }

    @Test
    fun `the bar never draws wider than its dp budget`() {
        for (zoom in 0..22) {
            for (latitude in listOf(-85.0, -37.58, 0.0, 37.58, 60.0, 85.0)) {
                val metersPerDp = metersPerDp(zoom.toDouble(), latitude)
                for (imperial in listOf(false, true)) {
                    val (barMeters, label) = scaleBar(metersPerDp * MAX_BAR_DP, imperial)
                    val widthDp = barMeters / metersPerDp
                    assertTrue(
                        widthDp in 0.0..MAX_BAR_DP,
                        "z$zoom lat$latitude \"$label\" wants $widthDp dp of $MAX_BAR_DP",
                    )
                }
            }
        }
    }
}
