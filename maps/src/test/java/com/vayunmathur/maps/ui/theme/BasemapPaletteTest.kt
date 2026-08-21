package com.vayunmathur.maps.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Legible against the basemap" is the whole reason these colours are hardcoded rather than
 * derived from the scheme, but it was only ever an assertion in a comment. These make it an
 * assertion the build checks.
 *
 * Contrast is WCAG relative luminance, the same ratio the accessibility guidelines use. Text
 * has to clear 4.5:1 against the surface it sits on; large fills only have to be
 * distinguishable from their neighbours, which is a much weaker claim and is checked as such.
 */
class BasemapPaletteTest {

    /** WCAG 2.x relative luminance. */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun `every label clears 4_5 to 1 against its own halo`() {
        for (role in BasemapPalette.Label.entries) {
            val colors = BasemapPalette.label(role)
            val ratio = contrast(colors.text, colors.halo)
            assertTrue(
                ratio >= 4.5,
                "label $role text/halo contrast is $ratio, below 4.5:1",
            )
        }
    }

    /**
     * A label's halo only covers the glyph outline, so the text also has to survive against
     * the terrain it crosses. Background is the worst case: it is the largest area and the
     * one a label is most likely to sit on.
     */
    @Test
    fun `every label clears 4_5 to 1 against the background fill`() {
        val background = BasemapPalette.fill(BasemapPalette.Fill.Background)
        for (role in BasemapPalette.Label.entries) {
            val ratio = contrast(BasemapPalette.label(role).text, background)
            assertTrue(
                ratio >= 4.5,
                "label $role does not clear 4.5:1 against the basemap background (got $ratio)",
            )
        }
    }

    /** Roads have to be findable against the terrain they run over. */
    @Test
    fun `roads are distinguishable from the background`() {
        val background = BasemapPalette.fill(BasemapPalette.Fill.Background)
        for (role in listOf(
            BasemapPalette.Fill.RoadMajor,
            BasemapPalette.Fill.RoadMinor,
            BasemapPalette.Fill.Boundaries,
        )) {
            val ratio = contrast(BasemapPalette.fill(role), background)
            assertTrue(ratio >= 1.3, "fill $role is indistinguishable from background ($ratio)")
        }
    }

    @Test
    fun `every role resolves and every fill is opaque`() {
        for (role in BasemapPalette.Fill.entries) {
            assertEquals(1f, BasemapPalette.fill(role).alpha, "fill $role is translucent")
        }
    }

    /**
     * The prefix rules are order-dependent — `roads_highway_casing_early` contains both
     * "casing" and "highway", and casing has to win. Every id here is a real one from
     * `assets/style.json`.
     */
    @Test
    fun `layer ids resolve to the role they look like`() {
        val expected = mapOf(
            "background" to BasemapPalette.Fill.Background,
            "earth" to BasemapPalette.Fill.Background,
            "water" to BasemapPalette.Fill.Water,
            "water_river" to BasemapPalette.Fill.Waterway,
            "landuse_park" to BasemapPalette.Fill.Park,
            "landuse_runway" to BasemapPalette.Fill.Airstrip,
            "roads_taxiway" to BasemapPalette.Fill.Airstrip,
            "buildings" to BasemapPalette.Fill.Buildings,
            "boundaries_country" to BasemapPalette.Fill.Boundaries,
            "roads_rail" to BasemapPalette.Fill.Rail,
            // Casing before highway/major/link, or these three land on RoadMajor.
            "roads_highway_casing_early" to BasemapPalette.Fill.RoadCasing,
            "roads_major_casing_late" to BasemapPalette.Fill.RoadCasing,
            "roads_tunnels_link_casing" to BasemapPalette.Fill.RoadCasing,
            "roads_tunnels_highway" to BasemapPalette.Fill.RoadTunnel,
            "roads_highway" to BasemapPalette.Fill.RoadMajor,
            "roads_minor" to BasemapPalette.Fill.RoadMinor,
            "pois" to BasemapPalette.Fill.Other,
        )
        for ((id, role) in expected) {
            assertEquals(role, BasemapPalette.fillRole(id), "wrong fill role for '$id'")
        }
    }

    @Test
    fun `label ids resolve to the role they look like`() {
        assertEquals(BasemapPalette.Label.Locality, BasemapPalette.labelRole("places_locality"))
        assertEquals(BasemapPalette.Label.Water, BasemapPalette.labelRole("water_label_lakes"))
        assertEquals(BasemapPalette.Label.Road, BasemapPalette.labelRole("roads_labels_major"))
        assertEquals(BasemapPalette.Label.Other, BasemapPalette.labelRole("roads_oneway"))
    }
}
