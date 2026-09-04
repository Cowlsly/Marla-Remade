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
import com.vayunmathur.maps.data.ParkingSpot

/** Pin id — hit-tested in MapSurface.onMapClick so tapping the parking pin
 *  opens the parking sheet (Vela's parking pin). */
const val PARKING_PIN_LAYER_ID = "parking-pin"

/** Parking blue, distinct from the saved-place and search-result pins. */
private val PARKING_COLOR = Color(0xFF1967D2)
private val PIN_SIZE = 28.dp

/**
 * Parking-pin overlay (P9): the single active [ParkingSpot] drawn as plain Compose over
 * VectorMap. Renders nothing when no spot is saved.
 *
 * Was a GeoJSON + CircleLayer + SymbolLayer ("P" bitmap glyph); the renderer has no
 * vector-layer API, so the pin is a circle + Text glyph positioned via Projection.
 */
@Composable
fun ParkingLayer(spot: ParkingSpot?, cameraState: CameraState) {
    val s = spot ?: return
    val projection = cameraState.projection ?: return
    val offset = projection.screenLocationFromPosition(GeoPoint(s.lon, s.lat))
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset(offset.x - PIN_SIZE / 2, offset.y - PIN_SIZE / 2)
                .size(PIN_SIZE)
                .background(PARKING_COLOR, CircleShape)
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("P", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}
