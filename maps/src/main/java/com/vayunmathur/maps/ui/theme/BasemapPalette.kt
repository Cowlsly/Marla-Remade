package com.vayunmathur.maps.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colours for things drawn on the *tiles* rather than on the app's surfaces.
 *
 * These were hardcoded for a reason, even if an unstated one: `colorScheme.onSurface` is the
 * wrong reference for a road label, because a road label does not sit on a surface — it sits
 * on the basemap, whose colours this file also owns. Deriving them from the wallpaper would
 * let an unlucky accent produce grey-on-grey terrain, and nothing in the scheme expresses
 * "legible against whatever the terrain happens to be".
 *
 * So the basemap gets its own closed palette, contrast-checked against itself. Only the dark
 * palette exists: light mode uses the Protomaps style's own paint verbatim, and the runtime
 * recolour is what dark mode is.
 *
 * Values are `Color`, not hex strings, so they can be contrast-tested. They cross into the
 * style JSON through [toStyleHex] at a single boundary.
 */
object BasemapPalette {

    /** What a basemap layer *is*, independent of what the style happens to call it. */
    enum class Fill {
        Background,
        Water,
        Waterway,
        Landcover,
        Park,
        UrbanGreen,
        Hospital,
        Industrial,
        School,
        Beach,
        Zoo,
        Aerodrome,
        Airstrip,
        Pedestrian,
        Pier,
        LanduseOther,
        Buildings,
        Boundaries,
        Rail,
        RoadCasing,
        RoadTunnel,
        RoadMajor,
        RoadMinor,
        Other,
    }

    /** What a label layer is. Each carries a text colour and its halo. */
    enum class Label {
        Locality,
        Country,
        Region,
        Subplace,
        Islands,
        Water,
        Shields,
        Road,
        Other,
    }

    @Immutable
    data class LabelColors(val text: Color, val halo: Color)

    private val darkFill: Map<Fill, Color> = mapOf(
        Fill.Background to Color(0xFF1B1D22),
        Fill.Water to Color(0xFF0D1B2A),
        Fill.Waterway to Color(0xFF24455F),
        Fill.Landcover to Color(0xFF1F2A22),
        Fill.Park to Color(0xFF1E2B20),
        Fill.UrbanGreen to Color(0xFF23362A),
        Fill.Hospital to Color(0xFF2B2528),
        Fill.Industrial to Color(0xFF20262B),
        Fill.School to Color(0xFF282520),
        Fill.Beach to Color(0xFF2C2A22),
        Fill.Zoo to Color(0xFF213030),
        Fill.Aerodrome to Color(0xFF212228),
        Fill.Airstrip to Color(0xFF2B2D33),
        Fill.Pedestrian to Color(0xFF242229),
        Fill.Pier to Color(0xFF202225),
        Fill.LanduseOther to Color(0xFF1F2126),
        Fill.Buildings to Color(0xFF22262C),
        Fill.Boundaries to Color(0xFF4A4F57),
        Fill.Rail to Color(0xFF3A3E45),
        Fill.RoadCasing to Color(0xFF111318),
        Fill.RoadTunnel to Color(0xFF2B2E35),
        Fill.RoadMajor to Color(0xFF464B54),
        Fill.RoadMinor to Color(0xFF34383F),
        Fill.Other to Color(0xFF26282E),
    )

    private val darkHalo = Color(0xFF101216)

    private val darkLabel: Map<Label, LabelColors> = mapOf(
        Label.Locality to LabelColors(Color(0xFFE4E8EE), darkHalo),
        Label.Country to LabelColors(Color(0xFF9AA0AA), darkHalo),
        Label.Region to LabelColors(Color(0xFF80868F), darkHalo),
        Label.Subplace to LabelColors(Color(0xFFB0B6C0), darkHalo),
        Label.Islands to LabelColors(Color(0xFF9AA0AA), darkHalo),
        // Blue-tinted so a lake name reads as belonging to the water it sits on.
        Label.Water to LabelColors(Color(0xFF6F8FCE), Color(0xFF0D1B2A)),
        Label.Shields to LabelColors(Color(0xFFC8CCD4), darkHalo),
        Label.Road to LabelColors(Color(0xFFB8BDC6), darkHalo),
        Label.Other to LabelColors(Color(0xFFC9CED6), darkHalo),
    )

    fun fill(role: Fill): Color = darkFill.getValue(role)

    fun label(role: Label): LabelColors = darkLabel.getValue(role)

    /**
     * Which [Fill] a style layer id denotes.
     *
     * Prefix and substring matching, carried over unchanged from the code this replaced.
     * It is fragile — a renamed layer silently falls through to [Fill.Other] rather than
     * failing — and gets replaced by an explicit id map in the basemap-correctness pass.
     * Casing has to be tested before highway/major because `roads_highway_casing_early`
     * contains both tokens.
     */
    fun fillRole(id: String): Fill = when {
        id == "background" || id == "earth" -> Fill.Background
        id == "water" -> Fill.Water
        id == "water_stream" || id == "water_river" -> Fill.Waterway
        id == "landcover" -> Fill.Landcover
        id == "landuse_park" -> Fill.Park
        id == "landuse_urban_green" -> Fill.UrbanGreen
        id == "landuse_hospital" -> Fill.Hospital
        id == "landuse_industrial" -> Fill.Industrial
        id == "landuse_school" -> Fill.School
        id == "landuse_beach" -> Fill.Beach
        id == "landuse_zoo" -> Fill.Zoo
        id == "landuse_aerodrome" -> Fill.Aerodrome
        id == "landuse_runway" -> Fill.Airstrip
        id == "landuse_pedestrian" -> Fill.Pedestrian
        id == "landuse_pier" -> Fill.Pier
        id.startsWith("landuse") -> Fill.LanduseOther
        id == "buildings" -> Fill.Buildings
        id.startsWith("boundaries") -> Fill.Boundaries
        id == "roads_rail" -> Fill.Rail
        id.startsWith("roads_runway") || id.startsWith("roads_taxiway") -> Fill.Airstrip
        id.contains("casing") -> Fill.RoadCasing
        id.startsWith("roads_tunnels") -> Fill.RoadTunnel
        id.contains("highway") || id.contains("major") || id.contains("link") -> Fill.RoadMajor
        id.startsWith("roads") -> Fill.RoadMinor
        else -> Fill.Other
    }

    /** Which [Label] a style layer id denotes. Same caveat as [fillRole]. */
    fun labelRole(id: String): Label = when (id) {
        "places_locality" -> Label.Locality
        "places_country" -> Label.Country
        "places_region" -> Label.Region
        "places_subplace" -> Label.Subplace
        "earth_label_islands" -> Label.Islands
        "water_waterway_label", "water_label_ocean", "water_label_lakes" -> Label.Water
        "roads_shields" -> Label.Shields
        "roads_labels_major", "roads_labels_minor", "address_label" -> Label.Road
        else -> Label.Other
    }

    /** Dark fill/line/background colour for a base style layer, as a style string. */
    fun darkFillHex(id: String): String = fill(fillRole(id)).toStyleHex()

    /** Dark `(text-color, text-halo-color)` for a label layer, as style strings. */
    fun darkLabelHex(id: String): Pair<String, String> =
        label(labelRole(id)).let { it.text.toStyleHex() to it.halo.toStyleHex() }
}
