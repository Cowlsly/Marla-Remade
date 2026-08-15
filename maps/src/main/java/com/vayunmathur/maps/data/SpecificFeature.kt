package com.vayunmathur.maps.data

import com.vayunmathur.maps.util.Wikidata
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
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
    data class TransitStop(override val name: String, val stopCode: String?, val gtfsFeed: String?, override val position: Position): RoutableFeature
    @Serializable
    data class Route(val waypoints: List<RoutableFeature?>) : SpecificFeature
}

typealias Feature1 = Feature<Geometry, JsonObject?>

fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

suspend fun parse(feature: Feature1, repository: com.vayunmathur.maps.data.AmenityRepository): SpecificFeature? {
    return parse(feature, repository.getDatabase())
}

suspend fun parse(feature: Feature1, db: AmenityDatabase): SpecificFeature? {
    val id = feature.id?.jsonPrimitive?.content?.toULong() ?: 0uL
    val geometry = feature.geometry
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
        "restaurant", "fast_food", "cafe", "bar" -> {
            val point = geometry as? Point ?: return null
            val tags = db.tagDao().getTags(id.toLong()).associate { it.key to it.value }
            SpecificFeature.Restaurant(tags["name"] ?: "", tags["phone"], tags["website"], tags["website:menu"], tags["opening_hours"]?.let { OpeningHours.from(it) }, point.coordinates)
        }
        "bus_stop", "bus_station", "tram_stop", "railway_station", "subway_entrance" -> {
            val point = geometry as? Point ?: return null
            val tags = db.tagDao().getTags(id.toLong()).associate { it.key to it.value }
            val gtfsTag = tags.keys.find { it.startsWith("gtfs:stop_code:") }
            val gtfsFeed = gtfsTag?.removePrefix("gtfs:stop_code:")
            val stopCode = gtfsTag?.let { tags[it] }
            SpecificFeature.TransitStop(tags["name"] ?: "", stopCode, gtfsFeed, point.coordinates)
        }
        !in listOf(
            "country", "region", "county", "locality", "address", "building", "building_part",
            "barren", "farmland", "forest", "glacier", "grassland", "scrub", "urban_area",
            "earth", "aerodrome", "attraction", "bare_rock", "beach", "cafe", "camp_site",
            "cemetery", "college", "commercial", "dog_park", "farmyard", "footway", "garden",
            "golf_course", "grass", "grocery", "hospital", "hotel", "industrial", "kindergarten",
            "library", "marina", "meadow", "military", "national_park", "nature_reserve",
            "neighbourhood", "orchard", "other", "park", "pedestrian", "pier", "pitch",
            "platform", "playground", "post_office", "protected_area", "railway", "runway",
            "recreation_ground", "residential", "sand", "school", "stadium", "supermarket",
            "taxiway", "townhall", "university", "wetland", "wood", "zoo", "macrohood",
            "highway", "major_road", "minor_road", "path", "aerialway", "ferry", "rail",
            "aeroway", "water", "lake", "playa", "ocean"
        ) -> {
            val point = geometry as? Point ?: return null
            val tags = db.tagDao().getTags(id.toLong()).associate { it.key to it.value }
            SpecificFeature.GenericPlace(tags["name"] ?: "", tags["phone"], tags["website"], tags["opening_hours"]?.let { OpeningHours.from(it) }, point.coordinates)
        }
        else -> null
    }
}