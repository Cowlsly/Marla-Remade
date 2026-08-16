package com.vayunmathur.maps.data

import com.vayunmathur.maps.util.Wikidata
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
    @Serializable
    data class Restaurant(override val name: String, val phone: String?, val website: String?, val menu: String?, val openingHours: OpeningHours?,
                          override val position: Position): RoutableFeature
    @Serializable
    data class GenericPlace(override val name: String, val phone: String?, val website: String?, val openingHours: OpeningHours?,
                          override val position: Position): RoutableFeature
    @Serializable
    data class Route(val waypoints: List<RoutableFeature?>) : SpecificFeature
}

typealias Feature1 = Feature<Geometry, JsonObject?>

fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

/**
 * Resolve a tapped basemap feature into a [SpecificFeature].
 *
 * Amenities are now Google-only (rendered on the custom overlay layer and tapped
 * there — see GooglePoiLayer), so this no longer reads the amenities DB: it only
 * handles the country/region admin labels (Wikidata-backed). Everything else
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
        else -> null
    }
}