package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.ipc.FamilyMember
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** Circle (dot) layer id — the reliable large tap target hit-tested in
 *  MapPage.onMapClick so a tapped family pin selects that person. */
const val FAMILY_LOCATION_LAYER_ID = "family-location-pins"
private const val FAMILY_LOCATION_INITIAL_LAYER_ID = "family-location-initials"
private const val FAMILY_LOCATION_NAME_LAYER_ID = "family-location-names"
private const val FAMILY_LOCATION_SOURCE_ID = "family-location-geojson"

/** Family accent (indigo) so people read distinctly from Google POIs (category
 *  colours), saved pins (blue), search results (red) and transit stops (teal). */
private val FAMILY_LOCATION_COLOR = Color(0xFF5E35B1)

/**
 * Live family-location overlay: a [GeoJsonSource] fed from the findfamily bound
 * service (pushed while the map is open) plus a [CircleLayer] avatar dot, a
 * centred initial and a name label, ported to maplibre-compose declarative
 * layers exactly like [SavedPlacesLayer] / [SearchResultLayer]. Tap the dot →
 * details via [toSelectedFamilyMember], reusing [SpecificFeature.GenericPlace]
 * so the existing place sheet + Directions/route path renders with no new code.
 */
@Composable
@MaplibreComposable
fun FamilyLocationLayer(members: List<FamilyMember>) {
    var source by remember { mutableStateOf<GeoJsonSource?>(null) }

    LaunchedEffect(Unit) {
        source = GeoJsonSource(
            FAMILY_LOCATION_SOURCE_ID,
            GeoJsonData.Features(FeatureCollection(emptyList<Feature1>())),
            GeoJsonOptions(),
        )
    }

    source?.let { src ->
        LaunchedEffect(members) {
            src.setData(GeoJsonData.Features(FeatureCollection(members.map { it.toFeature() })))
        }

        // Avatar dot (the pin body + tap target).
        CircleLayer(
            FAMILY_LOCATION_LAYER_ID,
            src,
            color = const(FAMILY_LOCATION_COLOR),
            radius = interpolate(
                linear(), zoom(),
                11 to const(8.dp),
                14 to const(11.dp),
                17 to const(14.dp),
            ),
            strokeColor = const(Color.White),
            strokeWidth = const(2.dp),
        )

        // Initial centred in the dot — the "avatar" glyph.
        SymbolLayer(
            FAMILY_LOCATION_INITIAL_LAYER_ID,
            src,
            textField = feature["initial"].cast<StringValue>(),
            textColor = const(Color.White),
            textAnchor = const(SymbolAnchor.Center),
        )

        // Name label below the dot.
        SymbolLayer(
            FAMILY_LOCATION_NAME_LAYER_ID,
            src,
            textField = feature["name"].cast<StringValue>(),
            textColor = const(FAMILY_LOCATION_COLOR),
            textHaloColor = const(Color.White),
            textHaloWidth = const(1.5.dp),
            textAnchor = const(SymbolAnchor.Top),
        )
    }
}

/** Rebuild a member as a GeoJSON point feature; name/initial ride along for the
 *  labels, id/lat/lng so onMapClick can re-select the person. */
private fun FamilyMember.toFeature(): Feature1 = Feature1(
    Point(Position(lng, lat)),
    JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "name" to JsonPrimitive(name),
            "initial" to JsonPrimitive(initialOf(name)),
            "lat" to JsonPrimitive(lat),
            "lng" to JsonPrimitive(lng),
        )
    ),
)

/** First letter of the display name, uppercased; "?" when the name is blank. */
private fun initialOf(name: String): String =
    name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

/**
 * Convert a hit-tested family pin back into a selectable place, reusing
 * [SpecificFeature.GenericPlace] (name + position) so the existing place sheet
 * renders and its Directions button routes to the person — no new detail path.
 */
fun Feature1.toSelectedFamilyMember(): SpecificFeature? {
    val props = properties ?: return null
    val name = props.string("name")?.ifBlank { null } ?: return null
    val lat = props["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
    val lng = props["lng"]?.jsonPrimitive?.doubleOrNull ?: return null
    return SpecificFeature.GenericPlace(name, null, null, null, Position(lng, lat))
}
