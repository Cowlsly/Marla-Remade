package com.vayunmathur.measure.domain

import com.vayunmathur.measure.data.model.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class UnitsTest {

    @Test
    fun `fractional inches reduce to lowest terms`() {
        // 8/16 must read 1/2, not 8/16.
        assertEquals("0 1/2\"", Units.formatFractionalInches(0.5))
        assertEquals("0 1/4\"", Units.formatFractionalInches(0.25))
        assertEquals("0 3/16\"", Units.formatFractionalInches(3.0 / 16.0))
        assertEquals("0 7/8\"", Units.formatFractionalInches(0.875))
    }

    @Test
    fun `whole inches omit the fraction`() {
        assertEquals("5\"", Units.formatFractionalInches(5.0))
        assertEquals("0\"", Units.formatFractionalInches(0.0))
    }

    @Test
    fun `inches roll over into feet`() {
        assertEquals("1' 0\"", Units.formatFractionalInches(12.0))
        assertEquals("2' 3 1/2\"", Units.formatFractionalInches(27.5))
    }

    @Test
    fun `rounding up a fraction carries into the next inch`() {
        // 0.99999 in rounds to 16/16, which must become 1 inch with no fraction.
        assertEquals("1\"", Units.formatFractionalInches(0.99999))
        assertEquals("1' 0\"", Units.formatFractionalInches(11.9999))
    }

    @Test
    fun `negative lengths keep their sign`() {
        assertEquals("-0 1/2\"", Units.formatFractionalInches(-0.5))
    }

    @Test
    fun `metric lengths switch unit by magnitude`() {
        assertEquals("5.0 mm", Units.formatLength(0.005, UnitSystem.Metric))
        assertEquals("250 mm", Units.formatLength(0.25, UnitSystem.Metric))
        assertEquals("2.500 m", Units.formatLength(2.5, UnitSystem.Metric))
        assertEquals("12.50 m", Units.formatLength(12.5, UnitSystem.Metric))
    }

    @Test
    fun `one inch in metres formats as one inch`() {
        val oneInchInMetres = Units.MM_PER_INCH / 1000.0
        assertEquals("1\"", Units.formatLength(oneInchInMetres, UnitSystem.Imperial))
    }

    @Test
    fun `bearing delta takes the short way round`() {
        assertEquals(-20.0, Units.bearingDelta(10.0, 350.0), 1e-9)
        assertEquals(20.0, Units.bearingDelta(350.0, 10.0), 1e-9)
        // An exact half-turn is ambiguous; the range is [-180, 180) so it resolves down.
        assertEquals(-180.0, Units.bearingDelta(0.0, 180.0), 1e-9)
    }

    @Test
    fun `degrees normalize into zero to 360`() {
        assertEquals(350.0, Units.normalizeDegrees(-10.0), 1e-9)
        assertEquals(10.0, Units.normalizeDegrees(370.0), 1e-9)
        assertEquals(0.0, Units.normalizeDegrees(360.0), 1e-9)
    }

    @Test
    fun `cardinal points map to the nearest sixteenth`() {
        assertEquals(0, Units.cardinalIndex(0.0))
        assertEquals(0, Units.cardinalIndex(359.0))
        assertEquals(2, Units.cardinalIndex(45.0))
        assertEquals(8, Units.cardinalIndex(180.0))
        assertEquals(12, Units.cardinalIndex(270.0))
    }

    @Test
    fun `area switches unit by magnitude`() {
        assertEquals("2.00 m²", Units.formatArea(2.0, UnitSystem.Metric))
        assertEquals("5000 cm²", Units.formatArea(0.5, UnitSystem.Metric))
    }
}
