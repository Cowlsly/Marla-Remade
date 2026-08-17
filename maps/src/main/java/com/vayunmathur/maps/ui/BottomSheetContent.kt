package com.vayunmathur.maps.ui
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.PrimaryTabRow
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconWork
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ipc.RideEstimateClient
import com.vayunmathur.maps.ipc.RideHandoffContract
import com.vayunmathur.maps.ipc.rememberRideEstimate
import com.vayunmathur.maps.util.NavigationService
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.formatDistance
import org.maplibre.spatialk.geojson.Position

@Composable
fun BottomSheetContent(
    viewModel: SelectedFeatureViewModel,
    selectedFeature: SpecificFeature?,
    setSelectedFeature: (SpecificFeature?) -> Unit,
    route: Map<RouteService.TravelMode, RouteService.RouteType?>?,
    selectedRouteType: RouteService.TravelMode,
    setSelectedRouteType: (RouteService.TravelMode) -> Unit,
    inactiveNavigation: SpecificFeature.Route?,
    savedPlacesViewModel: SavedPlacesViewModel,
    navState: NavigationSessionManager.NavState = NavigationSessionManager.NavState.Idle,
) {
    when (selectedFeature) {
        is SpecificFeature.Admin0Label -> {
            Column {
                Text(selectedFeature.name, style = MaterialTheme.typography.titleLarge)
                Text(selectedFeature.wikipedia, style = MaterialTheme.typography.bodyMedium)
            }
        }
        is SpecificFeature.Admin1Label -> {
            Column {
                Text(selectedFeature.name, style = MaterialTheme.typography.titleLarge)
                Text(selectedFeature.wikipedia, style = MaterialTheme.typography.bodyMedium)
            }
        }
        is SpecificFeature.Restaurant -> {
            Column {
                PlaceSheet(viewModel, savedPlacesViewModel, inactiveNavigation, selectedFeature) {
                    if(inactiveNavigation == null) {
                        setSelectedFeature(SpecificFeature.Route(listOf(null, selectedFeature)))
                    } else {
                        setSelectedFeature(SpecificFeature.Route(inactiveNavigation.waypoints + listOf(selectedFeature)))
                    }
                }
                SavedPlaceActions(selectedFeature, savedPlacesViewModel)
            }
        }
        is SpecificFeature.GenericPlace -> {
            Column {
                PlaceSheet(viewModel, savedPlacesViewModel, inactiveNavigation, selectedFeature) {
                    if (inactiveNavigation == null) {
                        setSelectedFeature(SpecificFeature.Route(listOf(null, selectedFeature)))
                    } else {
                        setSelectedFeature(
                            SpecificFeature.Route(
                                inactiveNavigation.waypoints + listOf(
                                    selectedFeature
                                )
                            )
                        )
                    }
                }
                SavedPlaceActions(selectedFeature, savedPlacesViewModel)
            }
        }
        is SpecificFeature.Route -> {
            if(route != null) {
                val userPosition by viewModel.userPosition.collectAsState()
                RouteSheet(selectedFeature, route, selectedRouteType, setSelectedRouteType, navState, userPosition)
            }
        }
        else -> Unit
    }
}

/**
 * The directions panel: one tab per travel mode, the trip summary, and the turn-by-turn
 * step list.
 *
 * Stateless (no ViewModel) so the store-listing previews can render it — the map it
 * normally sits over is a native surface a preview cannot draw.
 */
@Composable
fun RouteSheet(
    selectedFeature: SpecificFeature.Route,
    route: Map<RouteService.TravelMode, RouteService.RouteType?>,
    selectedRouteType: RouteService.TravelMode,
    setSelectedRouteType: (RouteService.TravelMode) -> Unit,
    navState: NavigationSessionManager.NavState = NavigationSessionManager.NavState.Idle,
    userPosition: Position? = null,
) {
    Column {
        PrimaryTabRow(route.entries.indexOfFirst { it.key == selectedRouteType }) {
            route.entries.forEach {
                Tab(
                    selectedRouteType == it.key,
                    { setSelectedRouteType(it.key) }) {
                    val label = when(it.key) {
                        RouteService.TravelMode.WALK -> stringResource(R.string.travel_mode_walk)
                        RouteService.TravelMode.BICYCLE -> stringResource(R.string.travel_mode_bicycle)
                        RouteService.TravelMode.DRIVE -> stringResource(R.string.travel_mode_drive)
                        RouteService.TravelMode.TRANSIT -> stringResource(R.string.travel_mode_transit)
                    }
                    Text(label)
                }
            }
        }
        val routeForMode = route[selectedRouteType]
        if(routeForMode != null) {
            if(routeForMode !is RouteService.EmptyRoute) {
                ListItem({ Text(routeForMode.duration.toString()) }, supportingContent = {
                    Text(formatDistance(routeForMode.distanceMeters))
                })
                // "Start Navigation" only when we have a concrete
                // Route (steps + polyline) and aren't already in
                // an active navigation session.
                //
                // We also hide the button for TRANSIT: the
                // navigation engine snaps GPS to the route
                // polyline and computes ETA from progress along
                // it, which doesn't model trains/buses well, and
                // mid-trip recalc would replace transit steps
                // with walking steps. Users can still see the
                // transit step list; we just don't offer to
                // "drive" them through it.
                val lastWaypoint = selectedFeature.waypoints.lastOrNull()
                val canStart = routeForMode is RouteService.Route &&
                        navState is NavigationSessionManager.NavState.Idle &&
                        selectedRouteType != RouteService.TravelMode.TRANSIT &&
                        lastWaypoint != null
                if (routeForMode is RouteService.Route &&
                    navState is NavigationSessionManager.NavState.Idle &&
                    selectedRouteType != RouteService.TravelMode.TRANSIT
                ) {
                    val context = LocalContext.current
                    Button(
                        onClick = {
                            if (lastWaypoint == null) return@Button
                            val destPos = lastWaypoint.position
                            val destName = lastWaypoint.name
                            val intent = Intent(context, NavigationService::class.java)
                            context.startForegroundService(intent)
                            NavigationSessionManager.init(context)
                            NavigationSessionManager.start(
                                route = routeForMode,
                                mode = selectedRouteType,
                                destination = destPos,
                                destinationLabel = destName,
                            )
                        },
                        enabled = canStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(stringResource(R.string.nav_action_start))
                    }
                }
                // Taxi/ride option (P20): offer a ride for this route's origin→destination
                // via the MA taxi app. Origin is the first waypoint (or the user's live
                // position when the route starts "from here"); destination is the last.
                val taxiOrigin = selectedFeature.waypoints.firstOrNull()
                val taxiDest = selectedFeature.waypoints.lastOrNull()
                // Origin may be "from here" (a null waypoint) → the user's live position; the
                // destination must be a real waypoint, so it never falls back to userPosition.
                val taxiOriginPos = taxiOrigin?.position ?: userPosition
                val taxiDestPos = taxiDest?.position
                if (selectedFeature.waypoints.size >= 2 && taxiOriginPos != null && taxiDestPos != null) {
                    RouteTaxiOption(
                        originLat = taxiOriginPos.latitude,
                        originLng = taxiOriginPos.longitude,
                        originLabel = taxiOrigin?.name,
                        destLat = taxiDestPos.latitude,
                        destLng = taxiDestPos.longitude,
                        destLabel = taxiDest.name,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (routeForMode) {
                    is RouteService.Route -> {
                        itemsIndexed(routeForMode.step) { idx, it ->
                            Card(shape = verticalShape(idx, routeForMode.step.size)) {
                                ListItem({
                                    Text(it.navInstruction.instructions)
                                }, leadingContent = {
                                    it.navInstruction.maneuver.iconContent()?.let { icon ->
                                        icon(Modifier, LocalContentColor.current)
                                    }
                                })
                            }
                        }
                    }

                    is RouteService.EmptyRoute -> {
                        item {
                            ListItem({
                                Text(stringResource(R.string.no_route_found))
                            })
                        }
                    }
                }
            }
        } else {
            ListItem({
                Text(stringResource(R.string.generating_route))
            })
        }
    }
}

/**
 * The taxi/ride option shown under a route (P20): if the MA taxi app is installed, offer a ride
 * for the route's origin→destination. When the signature-guarded estimate resolves, the fare/ETA
 * is shown inline; "Book ride" opens the taxi app with the trip pre-filled. If taxi is absent the
 * option renders nothing; if the estimate is unavailable it degrades to a launch-only button — no
 * crash either way.
 */
@Composable
private fun RouteTaxiOption(
    originLat: Double,
    originLng: Double,
    originLabel: String?,
    destLat: Double,
    destLng: Double,
    destLabel: String?,
) {
    val context = LocalContext.current
    val installed = remember { RideEstimateClient.isInstalled(context) }
    if (!installed) return
    val estimate by rememberRideEstimate(
        context, originLat, originLng, destLat, destLng, originLabel, destLabel,
    )
    val available = estimate?.takeIf { it.available }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            ListItem({ Text(stringResource(R.string.route_taxi_title)) }, supportingContent = {
                val subtitle = listOfNotNull(
                    available?.fareEstimate,
                    available?.etaMinutes?.let { stringResource(R.string.route_taxi_eta_minutes, it) },
                ).joinToString(" · ").ifBlank { stringResource(R.string.route_taxi_provider) }
                Text(subtitle)
            })
            Button(
                onClick = {
                    goto(
                        context,
                        RideHandoffContract.bookingDeepLink(
                            originLat, originLng, originLabel, destLat, destLng, destLabel,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(stringResource(R.string.route_taxi_book))
            }
        }
    }
}

/**
 * Two chips under a place's details letting the user pin it to Home or Work.
 * If the place is already saved in a slot, the chip is selected and tapping it
 * again removes it, so the same control handles setting, replacing and clearing.
 */
@Composable
fun SavedPlaceActions(
    feature: SpecificFeature.RoutableFeature,
    savedPlacesViewModel: SavedPlacesViewModel,
) {
    val home by savedPlacesViewModel.home.collectAsState()
    val work by savedPlacesViewModel.work.collectAsState()

    val isHome = home?.matches(feature) == true
    val isWork = work?.matches(feature) == true

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = isHome,
            onClick = { if (isHome) savedPlacesViewModel.clearHome() else savedPlacesViewModel.setHome(feature) },
            label = {
                Text(stringResource(if (isHome) R.string.remove_from_home else R.string.save_as_home))
            },
            leadingIcon = { IconHome(Modifier.size(18.dp)) },
        )
        FilterChip(
            selected = isWork,
            onClick = { if (isWork) savedPlacesViewModel.clearWork() else savedPlacesViewModel.setWork(feature) },
            label = {
                Text(stringResource(if (isWork) R.string.remove_from_work else R.string.save_as_work))
            },
            leadingIcon = { IconWork(Modifier.size(18.dp)) },
        )
    }
}