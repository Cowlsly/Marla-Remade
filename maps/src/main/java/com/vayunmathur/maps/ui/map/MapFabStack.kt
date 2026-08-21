package com.vayunmathur.maps.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconMyLocation
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.ui.CompassButton
import com.vayunmathur.maps.ui.LayersButton
import com.vayunmathur.maps.ui.MapScaleBar
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.R as MapsR

/**
 * The browse-mode map controls: a scale bar bottom-left, a FAB stack bottom-right.
 *
 * Stateless — every action is a lambda, so this file has no idea what a parking spot is or how
 * to animate a camera. Shown only while browsing; navigation has its own controls and a
 * selected place hands the bottom half of the screen to the sheet.
 */
@Composable
fun BoxScope.MapFabStack(
    zoom: Double,
    latitude: Double,
    bearing: Double,
    onResetNorth: () -> Unit,
    onLayers: () -> Unit,
    onParking: () -> Unit,
    onMyLocation: () -> Unit,
) {
    MapScaleBar(
        zoom = zoom,
        latitude = latitude,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(MapChromeMetrics.chromeMargin),
    )
    Column(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(MapChromeMetrics.chromeMargin),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(MapChromeMetrics.fabSpacing),
    ) {
        // Compass on top and only while the map is rotated; layers in the middle;
        // my-location is the primary action, so it sits closest to the thumb.
        CompassButton(bearing = bearing, onResetNorth = onResetNorth)
        LayersButton(onClick = onLayers)
        // Parking memory (P9): with no saved spot a tap saves the current location; with one,
        // it recenters and opens the sheet ("find my car"). Both live in [onParking] because
        // which one happens depends on state this component does not own.
        FloatingActionButton(onClick = onParking) {
            Text(stringResource(MapsR.string.parking_pin_glyph))
        }
        FloatingActionButton(onClick = onMyLocation) {
            IconMyLocation()
        }
    }
}
