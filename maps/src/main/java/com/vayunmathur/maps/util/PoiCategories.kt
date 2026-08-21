package com.vayunmathur.maps.util

/**
 * Shared metadata for the OSM POI `type` enum baked into the v5 `ma_pois`
 * source-layer and the `poi_index.bin` side file (P27). The numbering is the
 * stable type map defined in `scripts/maps/README.md` /
 * `scripts/maps/osm_ingest/src/tags.rs`
 * (0..49 categories, 255 = "other"); never renumber, only append.
 *
 * Kept in one place so the map render ([com.vayunmathur.maps.ui.MaPoisLayer],
 * icon colour + glyph) and the offline search ([PoiIndex], the result subtitle)
 * agree on how a numeric type maps to a colour / glyph / human label.
 */
object PoiCategories {
    /** Catch-all bucket for a recognised POI key with an unmapped value. */
    const val TYPE_OTHER: Int = 255

    /** Every type number the generator can emit, for building the icon switch. */
    val ALL_TYPES: List<Int> = (0..50).toList() + TYPE_OTHER

    /** Default min-zoom for any type not explicitly tiered below (least
     *  prominent — only shown once zoomed well in). */
    const val DEFAULT_MIN_ZOOM: Int = 16

    /**
     * Zoom level at which a POI category first appears on the map. Prominent,
     * navigation-worthy categories (hospitals, transit, parks, museums, hotels…)
     * surface early (low zoom); everyday retail surfaces progressively later.
     *
     * This staggers POIs across zoom so each urban zoom level stays roughly
     * equally dense: at z12 only landmarks show over a wide area, and as you zoom
     * in — showing less ground but more screen space per area — lower-tier
     * categories fill in, instead of the whole set popping in at one zoom and
     * decluttering (merging) away as you zoom out. Tiles start at z12, so 12 is
     * the earliest possible value.
     */
    fun minZoom(type: Int): Int = when (type) {
        // z12 — landmarks / orientation anchors, visible city-wide.
        10, 50, 12, 15, 38, 8, 23 -> 12
        //   hospital, station, park, attraction, museum, hotel, town hall
        // z13 — civic + culture + big destinations.
        14, 17, 18, 21, 22, 40, 11, 19, 33, 49 -> 13
        //   worship, cinema, theatre, police, fire, tourist info, school,
        //   library, department store, marketplace
        // z14 — high-utility everyday stops.
        0, 3, 5, 6, 7, 9, 13, 16, 20, 39 -> 14
        //   restaurant, bar, grocery, gas, pharmacy, bank, gym, parking,
        //   post office, office
        // z15 — common retail / food / clinics.
        1, 2, 4, 24, 25, 26, 28, 29, 30, 31, 32, 34, 35 -> 15
        //   cafe, fast food, shop, clothing, electronics, hardware, car,
        //   bakery, books, furniture, sports, dentist, doctor
        // z16 — long-tail specialty retail + everything else (DEFAULT_MIN_ZOOM).
        else -> DEFAULT_MIN_ZOOM
    }

    /** Human-readable category label (used as the search-result subtitle). */
    fun label(type: Int): String = when (type) {
        0 -> "Restaurant"
        1 -> "Cafe"
        2 -> "Fast food"
        3 -> "Bar"
        4 -> "Shop"
        5 -> "Grocery"
        6 -> "Gas station"
        7 -> "Pharmacy"
        8 -> "Hotel"
        9 -> "Bank"
        10 -> "Hospital"
        11 -> "School"
        12 -> "Park"
        13 -> "Gym"
        14 -> "Place of worship"
        15 -> "Attraction"
        16 -> "Parking"
        17 -> "Cinema"
        18 -> "Theatre"
        19 -> "Library"
        20 -> "Post office"
        21 -> "Police"
        22 -> "Fire station"
        23 -> "Town hall"
        24 -> "Clothing"
        25 -> "Electronics"
        26 -> "Hardware"
        27 -> "Beauty"
        28 -> "Car"
        29 -> "Bakery"
        30 -> "Books"
        31 -> "Furniture"
        32 -> "Sports"
        33 -> "Department store"
        34 -> "Dentist"
        35 -> "Doctor"
        36 -> "Veterinary"
        37 -> "Charging station"
        38 -> "Museum"
        39 -> "Office"
        40 -> "Tourist info"
        41 -> "Florist"
        42 -> "Jewelry"
        43 -> "Optician"
        44 -> "Laundry"
        45 -> "Pet"
        46 -> "Liquor"
        47 -> "Toys"
        48 -> "Gift"
        49 -> "Marketplace"
        50 -> "Station"
        else -> "Place"
    }

    /**
     * Category pin colour (hex `#RRGGBB`, consumed by `Color.parseColor`).
     * Grouped so related categories read as a family (food = warm, health =
     * red, retail = blue, nature/green, civic = slate …).
     */
    fun colorHex(type: Int): String = when (type) {
        0, 2, 29, 49 -> "#E8590C"          // food: restaurant / fast food / bakery / marketplace
        1 -> "#F9AB00"                      // cafe
        3, 46 -> "#A142F4"                  // bar / liquor
        5, 12, 37 -> "#34A853"             // grocery / park / charging (green)
        6 -> "#0F9D58"                      // gas station
        7, 10, 34, 35, 36 -> "#D93025"     // health: pharmacy / hospital / dentist / doctor / vet
        8 -> "#4285F4"                      // hotel
        9 -> "#188038"                      // bank
        11, 19, 38 -> "#F29900"            // education/culture: school / library / museum
        13 -> "#7E57C2"                     // gym
        14 -> "#8D6E63"                     // place of worship
        15, 40 -> "#FF6D00"                // attraction / tourist info
        16, 20, 21, 22, 23, 39 -> "#5F6368" // civic/parking/office (slate)
        50 -> "#3949AB"                      // transit station (indigo)
        17, 18 -> "#AB47BC"                // cinema / theatre
        24, 27, 41, 42, 47, 48 -> "#E91E63" // apparel/beauty/florist/jewelry/toys/gift
        25, 43 -> "#1A73E8"                // electronics / optician
        26, 31, 30, 45 -> "#795548"        // hardware / furniture / books / pet
        28 -> "#455A64"                     // car
        else -> "#1A73E8"                   // generic shop / department / sports / laundry / other
    }

    /** Single-character glyph drawn in the pin (a hint, not unique per type). */
    fun glyph(type: Int): String = when (type) {
        0 -> "R"; 1 -> "C"; 2 -> "F"; 3 -> "B"; 4 -> "S"; 5 -> "G"; 6 -> "\u26FD"
        7 -> "+"; 8 -> "H"; 9 -> "$"; 10 -> "H"; 11 -> "S"; 12 -> "P"; 13 -> "G"
        14 -> "W"; 15 -> "A"; 16 -> "P"; 17 -> "C"; 18 -> "T"; 19 -> "L"; 20 -> "P"
        21 -> "P"; 22 -> "F"; 23 -> "T"; 24 -> "C"; 25 -> "E"; 26 -> "H"; 27 -> "B"
        28 -> "C"; 29 -> "B"; 30 -> "B"; 31 -> "F"; 32 -> "S"; 33 -> "D"; 34 -> "D"
        35 -> "M"; 36 -> "V"; 37 -> "E"; 38 -> "M"; 39 -> "O"; 40 -> "i"; 41 -> "F"
        42 -> "J"; 43 -> "O"; 44 -> "L"; 45 -> "P"; 46 -> "L"; 47 -> "T"; 48 -> "G"
        49 -> "M"; else -> "\u2022"
    }
}
