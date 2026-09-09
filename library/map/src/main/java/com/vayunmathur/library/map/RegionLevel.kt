package com.vayunmathur.library.map

/**
 * Which rung of the administrative stack a region mask means.
 *
 * A mask is asked for by point, because nothing in the archive links a place label to its
 * outline. But a point is contained by every region above it at once — a city sits inside a
 * county inside a state inside a country — so the point alone cannot say which outline was
 * meant. Asking for the smallest was the first attempt and it always answers with the deepest
 * rung: tapping a state selects whichever county its label happens to sit in.
 *
 * The bands are OSM `admin_level` values, and match the names the tiler's boundary schema
 * gives them (`kind_for`): 1-2 country, 3-4 region, 5-6 county, 7+ locality. Counties have no
 * rung of their own because nothing in the app selects one.
 */
enum class RegionLevel(val min: Int, val max: Int) {
    /** A country. */
    COUNTRY(1, 2),

    /** A state, province or other first-level division. */
    REGION(3, 4),

    /** A city, town or neighbourhood.
     *
     * Deliberately starts above the county band. A city and the county sharing its name are
     * near enough the same shape that preferring the smaller one picks between them by
     * accident, and the same tap could resolve differently twice running.
     */
    LOCALITY(7, 12),
}

/**
 * A request to dim everything outside one administrative region.
 *
 * [position] is a point inside the region — a label's anchor or a search result — and [level]
 * says which rung of the stack containing that point was meant. Both travel together because
 * neither identifies a region on its own.
 */
data class RegionMask(val position: GeoPoint, val level: RegionLevel)
