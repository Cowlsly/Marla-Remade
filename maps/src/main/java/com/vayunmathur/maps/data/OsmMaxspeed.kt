package com.vayunmathur.maps.data

import kotlin.math.roundToInt

/**
 * A posted speed limit resolved from an OSM `maxspeed` tag value.
 *
 * Both unit variants are precomputed so the driving UI can render the badge in
 * whichever unit matches the speedometer without re-parsing. [displayIsMph]
 * reflects the unit the tag was authored in (US/UK data is mph, most of the
 * world is km/h) and is the sensible default display unit.
 */
data class PostedLimit(val kmh: Int, val mph: Int, val displayIsMph: Boolean)

private const val MPH_TO_KMH = 1.609344

/**
 * Parse an OSM `maxspeed` tag value into a [PostedLimit], or `null` when the
 * value carries no concrete numeric limit (`none`, `signals`, `walk`,
 * unrecognised country schemes, blank, …).
 *
 * Handles the common forms: bare `"50"` (km/h), `"50 mph"`, `"30 km/h"`, and a
 * subset of implicit country schemes like `"DE:urban"`. Faithful to Vela's
 * `OsmMaxspeed` behaviour: unknown/relative values degrade to `null` rather
 * than guessing.
 */
fun parseMaxspeed(raw: String?): PostedLimit? {
    if (raw.isNullOrBlank()) return null
    val s = raw.trim().lowercase()

    countryImplicitKmh[s]?.let { return fromKmh(it) }

    when (s) {
        "none", "signals", "variable", "walk", "unposted" -> return null
    }

    Regex("""(\d+(?:\.\d+)?)\s*mph""").find(s)?.let { m ->
        val v = m.groupValues[1].toDouble().roundToInt()
        if (v <= 0) return null
        return PostedLimit(kmh = (v * MPH_TO_KMH).roundToInt(), mph = v, displayIsMph = true)
    }

    // Bare number or explicit km/h ("50", "50 km/h", "50kmh").
    val num = Regex("""(\d+(?:\.\d+)?)""").find(s) ?: return null
    val v = num.groupValues[1].toDouble().roundToInt()
    if (v <= 0) return null
    return fromKmh(v)
}

private fun fromKmh(kmh: Int): PostedLimit =
    PostedLimit(kmh = kmh, mph = (kmh / MPH_TO_KMH).roundToInt(), displayIsMph = false)

/**
 * A small subset of OSM implicit country/context maxspeed schemes. Values are
 * approximate legal defaults in km/h. Kept intentionally small — anything not
 * listed returns `null` (no badge) rather than an inaccurate guess.
 */
private val countryImplicitKmh: Map<String, Int> = mapOf(
    "de:living_street" to 7,
    "de:urban" to 50,
    "de:rural" to 100,
    "de:motorway" to 130,
    "gb:nsl_single" to 97, // 60 mph
    "gb:nsl_dual" to 113,  // 70 mph
    "gb:motorway" to 113,  // 70 mph
    "fr:urban" to 50,
    "fr:rural" to 80,
    "fr:motorway" to 130,
    "it:urban" to 50,
    "it:rural" to 90,
    "it:motorway" to 130,
    "at:urban" to 50,
    "at:rural" to 100,
    "at:motorway" to 130,
    "ch:urban" to 50,
    "ch:rural" to 80,
    "ch:motorway" to 120,
)
