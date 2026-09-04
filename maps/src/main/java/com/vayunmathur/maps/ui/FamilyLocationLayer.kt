package com.vayunmathur.maps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.ipc.FamilyMember
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.spatialk.geojson.Position

/** Circle (dot) layer id — the reliable large tap target hit-tested in
 *  MapSurface.onMapClick so a tapped family pin selects that person. */
const val FAMILY_LOCATION_LAYER_ID = "family-location-pins"

private val PIN_SIZE = 28.dp

/** Family accent (indigo) so people read distinctly from Google POIs (category
 *  colours), saved pins (blue), search results (accent) and transit stops (teal). */
private val FAMILY_LOCATION_COLOR = Color(0xFF5E35B1)

/**
 * Live family-location overlay: the findfamily bound-service members drawn as plain Compose
 * over VectorMap — an avatar dot with the member's initial plus a name label. Tap the dot →
 * details via [toSelectedFamilyMember], reusing [SpecificFeature.GenericPlace] so the existing
 * place sheet + Directions/route path renders with no new code.
 *
 * Was GeoJSON + CircleLayer + two SymbolLayers (initial + name); the renderer has no
 * vector-layer API, so each member is a dot + labels positioned via Projection.
 */
@Composable
fun FamilyLocationLayer(members: List<FamilyMember>, cameraState: CameraState) {
    if (members.isEmpty()) return
    val projection = cameraState.projection ?: return
    Box(Modifier.fillMaxSize()) {
        for (member in members) {
            val offset = projection.screenLocationFromPosition(GeoPoint(member.lng, member.lat))
            Box(
                Modifier
                    .offset(offset.x - PIN_SIZE / 2, offset.y - PIN_SIZE / 2)
                    .size(PIN_SIZE)
                    .background(FAMILY_LOCATION_COLOR, CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initialOf(member.name),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                member.name,
                color = FAMILY_LOCATION_COLOR,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.offset(offset.x - PIN_SIZE / 2, offset.y + PIN_SIZE / 2 + 2.dp),
            )
        }
    }
}

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
