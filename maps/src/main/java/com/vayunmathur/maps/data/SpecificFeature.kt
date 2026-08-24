package com.vayunmathur.maps.data

import com.vayunmathur.maps.util.PoiIndex
import com.vayunmathur.maps.util.Wikidata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

@Serializable
sealed interface SpecificFeature {
    interface RoutableFeature : SpecificFeature {
        val position: Position
        val name: String
    }

    @Serializable
    data class Admin0Label(@SerialName("iso3166_1") val iso: String, val wikipedia: String, val name: String) : SpecificFeature
    @Serializable
    data class Admin1Label(@SerialName("iso3166_2") val iso: String, val wikipedia: String, val name: String) : SpecificFeature
    /**
     * A city / town. Unlike the country and region labels there is no ISO code to
     * key on — the baked `admin_city` layer carries only `name` / `name_en` — so
     * the border highlight matches on the name instead.
     */
    @Serializable
    data class Admin2Label(val wikipedia: String, val name: String) : SpecificFeature
    @Serializable
    data class Restaurant(override val name: String, val phone: String?, val website: String?, val menu: String?, val openingHours: OpeningHours?,
                          override val position: Position): RoutableFeature
    @Serializable
    data class GenericPlace(override val name: String, val phone: String?, val website: String?, val openingHours: OpeningHours?,
                          override val position: Position, val poiType: Int? = null,
                          /** Street address from the OSM `addr:*` tags, when we have them. */
                          val address: String? = null): RoutableFeature
    @Serializable
    data class Route(val waypoints: List<RoutableFeature?>) : SpecificFeature
}

/**
 * A place built from a coordinate, with whatever `poi_attrs.bin` knows about it.
 *
 * Every path that turns a name and a coordinate into a sheet goes through here, so
 * an offline search result opens the same populated sheet a tapped pin does — which
 * it did not before: search built these with every field null, so an offline result
 * showed a title and nothing else.
 *
 * Returns a bare place when the sidecar is absent or the point is not one of ours,
 * which is the same thing every caller used to produce unconditionally.
 *
 * Suspending, and on [Dispatchers.IO], because [PoiIndex.attributesNear] reads a mapped side
 * file. Every one of this function's call sites used to be on the main thread — including the
 * one inside the tap gesture handler — so a cold 316 MB mmap page-faulted on the UI thread.
 * That is true even now the lookup is a binary search rather than a scan: the scan was what
 * made it an ANR, the mmap is what makes it I/O.
 */
suspend fun osmPlace(
    name: String,
    position: Position,
    poiType: Int? = null,
): SpecificFeature.GenericPlace {
    val attrs = withContext(Dispatchers.IO) {
        PoiIndex.attributesNear(position.latitude, position.longitude, name)
    }
    return SpecificFeature.GenericPlace(
        name = name,
        phone = attrs?.phone,
        website = attrs?.website,
        // A string we could not parse would render as a confident week of "Closed",
        // so it is dropped in favour of whatever Google has.
        openingHours = attrs?.openingHours?.let(OpeningHours::from)?.takeIf { it.hasRules },
        position = position,
        poiType = poiType,
        address = attrs?.address,
    )
}

typealias Feature1 = Feature<Geometry, JsonObject?>

fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

/**
 * Resolve a tapped basemap feature into a [SpecificFeature].
 *
 * Amenities come from the baked `ma_pois` layer and are tapped there (see
 * [com.vayunmathur.maps.ui.toSelectedMaPoi]), so this no longer reads the amenities DB: it only
 * handles the country/region/city admin labels (Wikidata-backed). Everything else
 * returns null, since native POIs are suppressed in the style.
 */
suspend fun parse(feature: Feature1): SpecificFeature? {
    val properties = feature.properties ?: return null
    return when(properties.string("kind")) {
        "country" -> {
            // Each of these tags may be missing on tiles for small/disputed
            // territories or when Wikidata returns no result. Skip the feature
            // rather than crashing the bottom sheet.
            val wikidataId = properties.string("wikidata") ?: return null
            val wiki = try { Wikidata.get(wikidataId) } catch (_: Exception) { return null }
            val iso = wiki.getProperty("P297") ?: return null
            val wikipediaUrl = wiki.getWikipedia() ?: return null
            val name = properties.string("name:en") ?: properties.string("name") ?: return null
            SpecificFeature.Admin0Label(iso, wikipediaUrl, name)
        }
        "region" -> {
            val wikidataId = properties.string("wikidata") ?: return null
            val wiki = try { Wikidata.get(wikidataId) } catch (_: Exception) { return null }
            val iso = wiki.getProperty("P300") ?: return null
            val wikipediaUrl = wiki.getWikipedia() ?: return null
            val name = properties.string("name:en") ?: properties.string("name") ?: return null
            SpecificFeature.Admin1Label(iso, wikipediaUrl, name)
        }
        "locality" -> {
            // No ISO lookup: a city has no ISO 3166 code, so the Wikidata round
            // trip is only for the article URL.
            val wikidataId = properties.string("wikidata") ?: return null
            val wiki = try { Wikidata.get(wikidataId) } catch (_: Exception) { return null }
            val wikipediaUrl = wiki.getWikipedia() ?: return null
            val name = properties.string("name:en") ?: properties.string("name") ?: return null
            SpecificFeature.Admin2Label(wikipediaUrl, name)
        }
        else -> null
    }
}