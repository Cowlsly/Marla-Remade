package com.vayunmathur.measure.domain

import com.vayunmathur.measure.domain.HeldOrientation
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val G = 9.80665

class LevelMathTest {

    /**
     * Gravity as the device would read it when stood on an edge and tilted.
     *
     * [rotationDeg] is the roll about the screen axis: 0 upright, 90 left side down,
     * 180 upside down, 270 right side down. [errorDeg] is how far off level it is.
     */
    private fun standingGravity(rotationDeg: Double, errorDeg: Double = 0.0) =
        Math.toRadians(rotationDeg + errorDeg).let { a ->
            Triple(G * sin(a), -G * cos(a), 0.0)
        }

    @Test
    fun `upright portrait is detected and reads level`() {
        val (x, y, z) = standingGravity(0.0)
        val r = LevelMath.fromGravity(x, y, z)
        assertEquals(HeldOrientation.Portrait, r.orientation)
        assertEquals(0.0, r.edgeAngleDeg, 1e-6)
        assertTrue(!r.isFlat)
    }

    @Test
    fun `landscape left edge down is detected and reads level`() {
        val (x, y, z) = standingGravity(90.0)
        val r = LevelMath.fromGravity(x, y, z)
        assertEquals(HeldOrientation.LandscapeLeft, r.orientation)
        assertEquals(0.0, r.edgeAngleDeg, 1e-6)
    }

    @Test
    fun `upside down portrait is detected and reads level`() {
        val (x, y, z) = standingGravity(180.0)
        val r = LevelMath.fromGravity(x, y, z)
        assertEquals(HeldOrientation.PortraitUpsideDown, r.orientation)
        assertEquals(0.0, r.edgeAngleDeg, 1e-6)
    }

    @Test
    fun `landscape right edge down is detected and reads level`() {
        val (x, y, z) = standingGravity(270.0)
        val r = LevelMath.fromGravity(x, y, z)
        assertEquals(HeldOrientation.LandscapeRight, r.orientation)
        assertEquals(0.0, r.edgeAngleDeg, 1e-6)
    }

    @Test
    fun `the same tilt error reads identically in all four orientations`() {
        // This is the whole point of measuring against the nearest quarter turn:
        // a 3 degree slope is 3 degrees however the phone happens to be held.
        for (rotation in listOf(0.0, 90.0, 180.0, 270.0)) {
            val (x, y, z) = standingGravity(rotation, errorDeg = 3.0)
            val r = LevelMath.fromGravity(x, y, z)
            assertEquals(
                3.0,
                r.edgeAngleDeg,
                1e-6,
                "rotation $rotation should still report a 3 degree error",
            )
        }
    }

    @Test
    fun `tilt sign is consistent across orientations`() {
        for (rotation in listOf(0.0, 90.0, 180.0, 270.0)) {
            val (x, y, z) = standingGravity(rotation, errorDeg = -2.5)
            val r = LevelMath.fromGravity(x, y, z)
            assertEquals(-2.5, r.edgeAngleDeg, 1e-6, "rotation $rotation sign flipped")
        }
    }

    @Test
    fun `flat screen up is detected as flat`() {
        val r = LevelMath.fromGravity(0.0, 0.0, G)
        assertTrue(r.isFlat)
        assertEquals(HeldOrientation.Flat, r.orientation)
    }

    @Test
    fun `flat screen down is detected as flat`() {
        val r = LevelMath.fromGravity(0.0, 0.0, -G)
        assertTrue(r.isFlat)
        assertEquals(HeldOrientation.Flat, r.orientation)
    }

    @Test
    fun `a phone stood on edge is not treated as flat`() {
        val (x, y, z) = standingGravity(0.0)
        assertTrue(!LevelMath.fromGravity(x, y, z).isFlat)
    }

    @Test
    fun `flat surface tilt maps to pitch and roll`() {
        // Lying screen-up, tipped 5 degrees about the device X axis.
        val a = Math.toRadians(5.0)
        val r = LevelMath.fromGravity(0.0, G * sin(a), G * cos(a))
        assertEquals(5.0, r.pitchDeg, 1e-6)
        assertEquals(0.0, r.rollDeg, 1e-6)
    }

    @Test
    fun `ui rotation cancels out when autorotate followed the device`() {
        // Device on its left side (quadrant 1) and the activity rotated with it:
        // nothing left to correct.
        assertEquals(0f, LevelMath.uiRotation(quadrant = 1, displayRotationDeg = 90))
    }

    @Test
    fun `ui rotation compensates when autorotate is off`() {
        // Device on its left side but the activity stayed in portrait, so the bubble
        // has to be turned by the full quarter to stay horizontal in the world.
        assertEquals(90f, LevelMath.uiRotation(quadrant = 1, displayRotationDeg = 0))
        assertEquals(180f, LevelMath.uiRotation(quadrant = 2, displayRotationDeg = 0))
        assertEquals(270f, LevelMath.uiRotation(quadrant = 3, displayRotationDeg = 0))
    }

    @Test
    fun `ui rotation stays in range for negative quadrants`() {
        val v = LevelMath.uiRotation(quadrant = -1, displayRotationDeg = 0)
        assertTrue(v in 0f..359f, "got $v")
    }
}
