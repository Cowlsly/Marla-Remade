package com.vayunmathur.maps.ui.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.Projection
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.ipc.FamilyMember
import com.vayunmathur.maps.util.PoiIndex
import com.vayunmathur.maps.util.SearchResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/**
 * In-memory pin features for hit-testing.
 *
 * The pins are drawn as plain Compose over [VectorMap] (the renderer has no vector-layer
 * API), so there is no rendered layer to query. Instead [MapSurface] rebuilds the same
 * [Feature1]s the resolvers (`toSelected*`) already understand and feeds them to
 * [MapFeaturePicker] through a [FeatureSource] that checks screen bounds via
 * [Projection]. One builder per pin kind so the layer files stay drawing-only.
 */

/** Screen position of a point feature, or null when it has no point geometry. */
fun Feature1.screenPos(projection: Projection): DpOffset? {
    val pos = (geometry as? Point)?.coordinates ?: return null
    return projection.screenLocationFromPosition(GeoPoint(pos.longitude, pos.latitude))
}

/** The [features] whose screen position falls inside [box]. */
fun featuresInBox(
    features: List<Feature1>,
    box: DpRect,
    projection: Projection,
): List<Feature1> = features.filter { f ->
    val o = f.screenPos(projection) ?: return@filter false
    o.x in box.left..box.right && o.y in box.top..box.bottom
}

fun parkingPinFeature(spot: ParkingSpot): Feature1 = Feature1(
    Point(Position(spot.lon, spot.lat)),
    JsonObject(
        mapOf(
            "lat" to JsonPrimitive(spot.lat),
            "lng" to JsonPrimitive(spot.lon),
        )
    ),
)

fun searchPinFeature(result: SearchResult): Feature1 = Feature1(
    Point(Position(result.lon, result.lat)),
    JsonObject(
        mapOf(
            "id" to JsonPrimitive(result.id),
            "name" to JsonPrimitive(result.title),
            "lat" to JsonPrimitive(result.lat),
            "lng" to JsonPrimitive(result.lon),
            "category" to JsonPrimitive(result.category ?: ""),
        )
    ),
)

fun savedPinFeature(place: SavedPlace): Feature1 = Feature1(
    Point(Position(place.lon, place.lat)),
    JsonObject(
        mapOf(
            "name" to JsonPrimitive(place.name),
            "lat" to JsonPrimitive(place.lat),
            "lng" to JsonPrimitive(place.lon),
        )
    ),
)

fun familyPinFeature(member: FamilyMember): Feature1 = Feature1(
    Point(Position(member.lng, member.lat)),
    JsonObject(
        mapOf(
            "id" to JsonPrimitive(member.id),
            "name" to JsonPrimitive(member.name),
            "initial" to JsonPrimitive(initialOf(member.name)),
            "lat" to JsonPrimitive(member.lat),
            "lng" to JsonPrimitive(member.lng),
        )
    ),
)

private fun initialOf(name: String): String =
    name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

/**
 * An ambient POI as a feature, so [toSelectedMaPoi] resolves it exactly like a tapped
 * `ma_pois` tile feature did: name + point geometry + numeric `type`.
 */
fun poiPinFeature(poi: PoiIndex.PoiRecord): Feature1 = Feature1(
    Point(Position(poi.lon, poi.lat)),
    JsonObject(
        mapOf(
            "name" to JsonPrimitive(poi.name),
            "type" to JsonPrimitive(poi.type.toString()),
        )
    ),
)
