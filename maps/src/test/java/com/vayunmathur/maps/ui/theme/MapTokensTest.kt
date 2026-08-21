package com.vayunmathur.maps.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [toStyleHex] feeds colours into MapLibre, which parses CSS colour syntax. The trap it
 * exists to close is that `#aarrggbb` — the form Android's own `Color.parseColor` accepts —
 * is not valid CSS, so an alpha colour serialized the obvious way is silently mis-parsed.
 */
class MapTokensTest {

    @Test
    fun `opaque colours render as six-digit hex`() {
        assertEquals("#ff0000", Color(0xFFFF0000).toStyleHex())
        assertEquals("#000000", Color(0xFF000000).toStyleHex())
        assertEquals("#ffffff", Color(0xFFFFFFFF).toStyleHex())
        assertEquals("#1b1d22", Color(0xFF1B1D22).toStyleHex())
    }

    @Test
    fun `translucent colours render as rgba, never as eight-digit hex`() {
        // 0.5f is stored as 128/255, so the emitted value is the quantized one rounded to
        // two places — that is the whole reason precision is capped there.
        val hex = Color(0xFFFF0000).copy(alpha = 0.5f).toStyleHex()
        assertEquals("rgba(255,0,0,0.5)", hex)
        assertTrue(!hex.startsWith("#"), "alpha must not be emitted as hex: $hex")
    }

    /** The admin highlight fill is the real caller of the alpha path. */
    @Test
    fun `a low alpha keeps two decimals`() {
        assertEquals("rgba(255,0,0,0.12)", Color(0xFFFF0000).copy(alpha = 0.12f).toStyleHex())
    }

    @Test
    fun `formatting does not depend on the default locale`() {
        val original = java.util.Locale.getDefault()
        try {
            // A comma-decimal locale would otherwise emit "rgba(255,0,0,0,5)", which MapLibre
            // parses as a four-argument rgba and rejects.
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("rgba(255,0,0,0.5)", Color(0xFFFF0000).copy(alpha = 0.5f).toStyleHex())
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun `a fully transparent colour still round-trips as rgba`() {
        assertEquals("rgba(0,0,0,0)", Color.Transparent.toStyleHex())
    }

    /** Rounding, not truncation — 0.5/255 must not fall a channel short. */
    @Test
    fun `channels round to nearest`() {
        assertEquals("#808080", Color(0xFF808080).toStyleHex())
    }

    @Test
    fun `the two palettes differ everywhere it matters`() {
        val light = mapTokens(isDark = false)
        val dark = mapTokens(isDark = true)
        assertTrue(light.traffic.jam != dark.traffic.jam, "traffic ramp must adapt to the basemap")
        assertTrue(light.traffic.free != dark.traffic.free)
        assertTrue(light.transitMode.subway != dark.transitMode.subway)
        assertTrue(light.routeInert != dark.routeInert)
    }

    /** A road sign has no dark variant, and this is where that stops being a comment. */
    @Test
    fun `the speed limit sign is identical in both palettes`() {
        assertEquals(mapTokens(isDark = false).speedSign, mapTokens(isDark = true).speedSign)
        assertEquals(SpeedSign, mapTokens(isDark = true).speedSign)
    }
}
