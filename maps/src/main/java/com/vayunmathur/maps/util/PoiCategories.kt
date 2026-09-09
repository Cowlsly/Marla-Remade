package com.vayunmathur.maps.util

/**
 * Shared metadata for the OSM POI `type` enum baked into the v5 `ma_pois`
 * source-layer and the `poi_index.bin` side file (P27). The numbering is the
 * stable type map defined in `scripts/maps/README.md` /
 * `scripts/maps/osm_ingest/src/tags.rs`
 * (0..49 categories, 255 = "other"); never renumber, only append.
 *
 * Kept in one place so the offline search ([PoiIndex], the result subtitle) and the
 * archive's own POI vocabulary ([typeOfKind]) agree on what a numeric type means.
 *
 * The map no longer renders from these numbers — the renderer draws POIs from the archive by
 * kind name, with its own sprite sheet and per-kind zoom gating. The pin colour, glyph and
 * per-category min-zoom that used to live here went with it; what is left is the vocabulary
 * the offline index still speaks.
 */
object PoiCategories {
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
     * The numeric type an archive `kind` corresponds to, or `null` when nothing here means
     * the same thing.
     *
     * The two vocabularies were designed independently — these numbers come from
     * `osm_ingest`'s tag table and predate the archive's `poi` layer by a long way — so this
     * is a best-effort join, not a bijection. Several archive kinds (`beach`, `peak`,
     * `bench`, `artwork`, `building`) describe things this enum never had a bucket for, and
     * several numbers here (`pharmacy`, `police`, `parking`) name things the archive does not
     * draw. Both directions lose.
     *
     * It exists because the numeric type is still load-bearing downstream: `PoiCategories.label`
     * writes the sheet's subtitle from it, and a tapped `station` opens a departure board by
     * matching type 50. Mapping the kind back to a number keeps those working unchanged
     * rather than making every consumer learn a second vocabulary.
     */
    fun typeOfKind(kind: String): Int? = when (kind) {
        "restaurant" -> 0
        "cafe" -> 1
        "fast_food" -> 2
        "bar" -> 3
        // The archive draws no separate grocery kind; a corner shop is the closest thing.
        "supermarket", "convenience" -> 5
        "fuel" -> 6
        "hotel" -> 8
        // An ATM is nearly always a bank's, and this enum has no separate number for one.
        "bank", "atm" -> 9
        // `university` folds into school: the enum has one education bucket.
        "school", "university" -> 11
        "park", "garden" -> 12
        "attraction", "zoo" -> 15
        "theatre" -> 18
        "library" -> 19
        "post_office" -> 20
        "townhall" -> 23
        "clothes" -> 24
        "electronics" -> 25
        "beauty" -> 27
        "books" -> 30
        "stadium" -> 32
        "animal" -> 36
        "museum" -> 38
        // The one mapping with behaviour attached: a tapped station opens the departure
        // board. `bus_stop` and `ferry_terminal` have no number here — this table is
        // `osm_ingest`'s, and it treats a bus pole or a ferry pier as street furniture
        // rather than a POI — so they route to the board by kind instead; see
        // [opensDepartureBoard].
        "station" -> STATION_TYPE
        else -> null
    }

    /**
     * The station type, whose taps open a departure board rather than a place sheet.
     *
     * Station POIs carry no stop id of their own; see `TransitStopsViewModel.openNearestStop`.
     */
    const val STATION_TYPE: Int = 50

    /**
     * Archive `poi` kinds whose taps open a departure board rather than a place sheet.
     *
     * Matched on the kind rather than on [typeOfKind]'s number because two of the three have
     * no number to match: `osm_ingest`'s table predates the archive and treats a bus pole or a
     * ferry pier as street furniture rather than a POI, so only `station` ever reaches 50.
     * The archive draws all three, and a tap on any of them is asking the same question.
     *
     * `bus_stop` covers tram stops too — the tiler folds `railway=tram_stop` into that kind.
     *
     * None of them carry a stop id, so the board is resolved from the nearest stop in the
     * baked pack; see `TransitStopsViewModel.openNearestStop`.
     */
    fun opensDepartureBoard(kind: String): Boolean =
        kind == "station" || kind == "bus_stop" || kind == "ferry_terminal"
}
