package com.vayunmathur.maps.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.FreeHeightSheetState
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ipc.FamilyMember
import com.vayunmathur.maps.ui.MyMapLayers
import com.vayunmathur.maps.ui.map.style.MapStyle
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SearchResult
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.TransitStopsViewModel
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import com.vayunmathur.maps.R as MapsR

/**
 * The map surface: the renderer, its overlay layers, and what a tap on it means.
 *
 * While the style is still being read and patched this shows a spinner rather than nothing —
 * a blank map and a broken map used to look identical.
 */
@Composable
fun MapSurface(
    style: MapStyle,
    camera: CameraState,
    chrome: MapChromeState,
    viewModel: SelectedFeatureViewModel,
    searchViewModel: MapsSearchViewModel,
    transitViewModel: TransitStopsViewModel,
    sheetState: FreeHeightSheetState,
    selectedFeature: SpecificFeature?,
    route: RouteService.RouteType?,
    userPosition: org.maplibre.spatialk.geojson.Position,
    userBearing: Float,
    navProgress: NavigationProgress?,
    searchResults: List<SearchResult>,
    savedPlaces: List<SavedPlace>,
    parkingSpot: ParkingSpot?,
    familyMembers: List<FamilyMember>,
    trafficEnabled: Boolean,
    satelliteEnabled: Boolean,
    safetyEnabled: Boolean,
    transitEnabled: Boolean,
    darkBasemap: Boolean,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()

    when (style) {
        MapStyle.Loading -> Box(modifier.fillMaxSize()) {
            LoadingState(message = stringResource(MapsR.string.map_loading))
        }

        is MapStyle.Ready -> MaplibreMap(
            modifier,
            BaseStyle.Json(style.json),
            camera,
            options = MapOptions(
                // TextureView, not the default SurfaceView: a SurfaceView renders into a
                // separate surface and goes black + stops taking input after this composable
                // is disposed pushing to SearchPage and recomposed on the back-pop through
                // Nav3's AnimatedContent transition — it only repaints once a later
                // recomposition forces a relayout. TextureView draws in the normal view
                // hierarchy, so it composites and stays interactive across the transition.
                RenderOptions(renderMode = RenderOptions.RenderMode.TextureView),
                GestureOptions.Standard,
                OrnamentOptions.AllDisabled,
            ),
            onMapClick = { latLng, offset ->
                coroutineScope.launch {
                    val projection = camera.projection
                    val picker = MapFeaturePicker(
                        source = { box, layerIds ->
                            projection?.queryRenderedFeatures(box, layerIds) ?: emptyList()
                        },
                        transitEnabled = transitEnabled,
                    )

                    when (val hit = picker.pickPin(offset)) {
                        MapHit.Parking -> {
                            chrome.show(MapOverlay.Parking)
                            return@launch
                        }
                        is MapHit.Stop -> {
                            transitViewModel.openStop(hit.stop)
                            return@launch
                        }
                        is MapHit.Place -> {
                            viewModel.stashRouteSelection()
                            viewModel.set(hit.feature)
                            sheetState.partialExpand()
                            return@launch
                        }
                        null -> Unit
                    }

                    // Fall back to the basemap's own place labels. Resolving one may make a
                    // Wikidata round-trip, so the ViewModel owns that rather than this handler.
                    val label = viewModel.resolveAdminLabel(picker.pickAdminLabels(offset))
                    if (label != null) {
                        viewModel.stashRouteSelection()
                        viewModel.set(label)
                        sheetState.partialExpand()
                        return@launch
                    }

                    // Nothing hit: reverse-geocode the point ("what's here?"). Online-only.
                    searchViewModel.reverseGeocode(latLng.latitude, latLng.longitude) { place ->
                        if (place != null) {
                            viewModel.stashRouteSelection()
                            viewModel.set(place)
                            coroutineScope.launch { sheetState.partialExpand() }
                        }
                    }
                }
                ClickResult.Pass
            },
        ) {
            MyMapLayers(
                selectedFeature,
                route,
                style.json,
                userPosition,
                userBearing,
                navProgress,
                searchResults,
                savedPlaces,
                parkingSpot,
                familyMembers,
                trafficEnabled,
                satelliteEnabled,
                safetyEnabled,
                transitEnabled,
                poiFilterTypes = chrome.selectedCategory?.types,
                darkBasemap = darkBasemap,
            )
        }
    }
}
