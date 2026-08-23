package com.vayunmathur.maps

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.downloadservice.InitialDownloadChecker
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.maps.data.MapLink
import com.vayunmathur.maps.data.MapLinkParser
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GoogleSearchDataSource
import com.vayunmathur.maps.ui.MapPage
import com.vayunmathur.maps.ui.SavedPlacesPage
import com.vayunmathur.maps.ui.SearchPage
import com.vayunmathur.maps.ui.settings.MapSettingsPage
import com.vayunmathur.maps.data.MapPreferences
import com.vayunmathur.maps.data.ThemeMode
import com.vayunmathur.maps.util.MapTileCache
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.NavigationService
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.MapSettingsViewModel
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.maplibre.android.log.Logger
import org.maplibre.spatialk.geojson.Position
import java.io.File

class MainActivity : ComponentActivity() {

    // Same instances the Compose tree gets via viewModel() (both resolve to this
    // Activity's ViewModelStore), so an external geo:/maps/navigation intent handled
    // here shows up in the map UI: selecting a place opens its bottom pane, and a
    // navigation link drives the shared NavigationSessionManager.
    private val selectedVm: SelectedFeatureViewModel by viewModels()
    private val searchVm: MapsSearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FIRST_PARTY: data.vayunmathur.com tiles + amenities + api.vayunmathur.com -> ISRG+GTS
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        enableEdgeToEdge()
        // Route MapLibre's HTTP (incl. the streamed pmtiles range requests)
        // through our disk-caching client. Must happen before the map loads.
        MapTileCache.install(this)
        // Reclaim the ~44 MB bundled basemap copied into filesDir by older
        // builds; the basemap now streams live and is cached on demand.
        File(filesDir, "world_z0-6.pmtiles").delete()
        val ds = DataStoreUtils.getInstance(this)
        Logger.setVerbosity(Logger.INFO)

        // Deep link that launched us cold (geo:/google.navigation:/maps URL).
        handleIntent(intent)

        setContent {
            val themeMode by ds.stringFlow(MapPreferences.KEY_THEME_MODE)
                .collectAsState(initial = ds.getString(MapPreferences.KEY_THEME_MODE))
            DynamicTheme(darkTheme = ThemeMode.from(themeMode).darkOverride) {
                InitialDownloadChecker(ds, listOf(
                    Triple("https://data.vayunmathur.com/metadata.bin", "metadata.bin", getString(R.string.downloading_navigation_metadata)),
                    Triple("https://data.vayunmathur.com/road_names.bin", "road_names.bin", getString(R.string.downloading_road_data)),
                    Triple("https://data.vayunmathur.com/nodes.bin", "nodes.bin", getString(R.string.downloading_road_data)),
                    Triple("https://data.vayunmathur.com/edges.bin", "edges.bin", getString(R.string.downloading_road_data)),
                    Triple("https://data.vayunmathur.com/lanes.bin", "lanes.bin", getString(R.string.downloading_road_data)),
                    // Per-edge geometry. Mandatory: the generator collapses
                    // degree-2 chains, so one edge is a whole road and this file
                    // is the only record of its shape. Graph.load refuses a pack
                    // without it rather than snapping to straight chords.
                    Triple("https://data.vayunmathur.com/intermediate.bin", "intermediate.bin", getString(R.string.downloading_road_data)),
                    Triple("https://data.vayunmathur.com/poi_index.bin", "poi_index.bin", getString(R.string.downloading_poi_data)),
                    Triple("https://data.vayunmathur.com/poi_names.bin", "poi_names.bin", getString(R.string.downloading_poi_data)),
                    // The POI attribute sidecar (opening hours / phone / website /
                    // address). REQUIRED here like the other two, so it must be
                    // hosted BEFORE this app version ships: InitialDownloadChecker
                    // gates on every file being present, and an entry with nothing
                    // behind it strands users on the download screen. PoiIndex
                    // itself treats the file as optional, so a device that somehow
                    // lacks it degrades to no attributes rather than breaking.
                    Triple("https://data.vayunmathur.com/poi_attrs.bin", "poi_attrs.bin", getString(R.string.downloading_poi_data)),
                    Triple("https://data.vayunmathur.com/world.transit", "world.transit", getString(R.string.downloading_transit_data))
                )) {
                    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // POST_NOTIFICATIONS is runtime-grantable on API 33+
                        // and required for the navigation foreground-service
                        // notification to actually display (otherwise the
                        // service runs invisibly and the user can't End Trip
                        // from outside the app).
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.POST_NOTIFICATIONS,
                        )
                    } else {
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    PermissionsChecker(perms, getString(R.string.grant_location_permission)) {
                        OfflineAware {
                            Navigation()
                        }
                    }
                }
            }
        }
    }

    // Warm start: the app was already running when another app fired a geo:/maps/
    // navigation intent at us. singleTop (see AndroidManifest) routes it here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Turn an external ACTION_VIEW `geo:` / `google.navigation:` / Google-Maps web
     * link into either an opened place (select + bottom pane) or a started
     * navigation session. Malformed/empty links are ignored (never crash).
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.dataString ?: return
        val link = MapLinkParser.parse(data)
        if (link == null) {
            Log.i(TAG, "ACTION_VIEW ignored (unparseable): $data")
            return
        }
        Log.i(TAG, "ACTION_VIEW routed data=$data -> $link")
        if (link.navigate) navigateTo(link) else openPlace(link)
    }

    /** Select a place from the link and open its bottom pane (Vela place-card). */
    private fun openPlace(link: MapLink) {
        val lat = link.lat
        val lng = link.lng
        val query = link.query
        when {
            // A point with a name/label → drop it straight in (no network).
            lat != null && lng != null && !query.isNullOrBlank() -> {
                selectedVm.selectAndFocus(genericPlace(query, lat, lng), link.zoom)
            }
            // A bare point → reverse-geocode for a name; fall back to a plain pin.
            lat != null && lng != null -> {
                searchVm.reverseGeocode(lat, lng) { place ->
                    selectedVm.selectAndFocus(
                        place ?: genericPlace(getString(R.string.dropped_pin), lat, lng),
                        link.zoom,
                    )
                }
            }
            // A query only → run the Google search and auto-select the first hit
            // (same path as the contact-address shortcut).
            !query.isNullOrBlank() -> {
                val near = biasPosition()
                searchVm.searchAndSelectFirst(query, near.latitude, near.longitude) { first ->
                    if (first != null) selectedVm.selectAndFocus(searchVm.toFeature(first), link.zoom)
                    else Log.i(TAG, "openPlace: no search result for \"$query\"")
                }
            }
        }
    }

    /** Resolve the link's destination, route from the user's location, and start guidance. */
    private fun navigateTo(link: MapLink) {
        lifecycleScope.launch {
            val mode = link.mode ?: RouteService.TravelMode.DRIVE
            // Destination position: an explicit coord, else geocode the query.
            val destPos: Position?
            val destName: String
            if (link.lat != null && link.lng != null) {
                destPos = Position(link.lng, link.lat)
                destName = link.query ?: getString(R.string.dropped_pin)
            } else if (!link.query.isNullOrBlank()) {
                val near = biasPosition()
                val hit = GoogleSearchDataSource.search(link.query, near.latitude, near.longitude).firstOrNull()
                destPos = hit?.let { Position(it.lng, it.lat) }
                destName = hit?.name ?: link.query
            } else {
                destPos = null
                destName = getString(R.string.dropped_pin)
            }
            if (destPos == null) {
                Log.i(TAG, "navigateTo: could not resolve destination for $link")
                return@launch
            }

            val destFeature = SpecificFeature.GenericPlace(
                name = destName, phone = null, website = null, openingHours = null, position = destPos,
            )
            // A null first waypoint means "from current location".
            val route = SpecificFeature.Route(listOf(null, destFeature))

            // Wait briefly for a real GPS fix so the first leg starts from the user.
            val origin = awaitUserPosition()
            if (origin == null) {
                // No fix yet: fall back to opening the destination place + pane so the
                // user can hit Start Navigation manually (which waits for location).
                Log.i(TAG, "navigateTo: no location fix; opening destination place instead")
                selectedVm.selectAndFocus(destFeature)
                return@launch
            }

            val computed = try {
                OfflineRouter.getRouteForMode(applicationContext, route, origin, mode)
            } catch (e: Exception) {
                Log.w(TAG, "navigateTo: routing failed", e); null
            }
            if (computed == null) {
                Log.i(TAG, "navigateTo: no route ($mode) to $destName; opening place instead")
                selectedVm.selectAndFocus(destFeature)
                return@launch
            }

            startForegroundService(Intent(applicationContext, NavigationService::class.java))
            NavigationSessionManager.init(applicationContext)
            NavigationSessionManager.start(
                route = computed,
                mode = mode,
                destination = destPos,
                destinationLabel = destName,
            )
            Log.i(TAG, "navigateTo: started $mode navigation to $destName")
        }
    }

    /** First valid GPS fix within [timeoutMs], or null. (0,0) means "no fix yet". */
    private suspend fun awaitUserPosition(timeoutMs: Long = 8_000): Position? =
        withTimeoutOrNull(timeoutMs) {
            selectedVm.userPosition.first { it.latitude != 0.0 || it.longitude != 0.0 }
        }

    /** Search bias: the user's live position when known, else the map's default centre. */
    private fun biasPosition(): Position {
        val p = selectedVm.userPosition.value
        return if (p.latitude != 0.0 || p.longitude != 0.0) p else Position(-118.243683, 34.052235)
    }

    private fun genericPlace(name: String, lat: Double, lng: Double) =
        SpecificFeature.GenericPlace(
            name = name, phone = null, website = null, openingHours = null,
            position = Position(lng, lat),
        )

    companion object {
        private const val TAG = "MapsIntent"
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object MapPage: Route
    @Serializable
    data object SettingsPage: Route
    @Serializable
    data object SavedPlacesPage: Route

    @Serializable
    data class SearchPage(val idx: Int?, val east: Double, val west: Double, val north: Double, val south: Double, val query: String? = null): Route
}

@Composable
fun Navigation(
    viewModel: SelectedFeatureViewModel = viewModel(),
    searchViewModel: MapsSearchViewModel = viewModel(),
    savedPlacesViewModel: SavedPlacesViewModel = viewModel(),
    settingsViewModel: MapSettingsViewModel = viewModel(),
    parkingViewModel: com.vayunmathur.maps.util.ParkingViewModel = viewModel(),
    transitViewModel: com.vayunmathur.maps.util.TransitStopsViewModel = viewModel(),
) {
    val backStack = rememberNavBackStack<Route>(Route.MapPage)
    MainNavigation(backStack) {
        entry<Route.MapPage> {
            MapPage(backStack, viewModel, savedPlacesViewModel, searchViewModel, settingsViewModel, parkingViewModel, transitViewModel)
        }
        entry<Route.SettingsPage> {
            MapSettingsPage(backStack, settingsViewModel)
        }
        entry<Route.SavedPlacesPage> {
            SavedPlacesPage(backStack, savedPlacesViewModel)
        }
        entry<Route.SearchPage> {
            SearchPage(backStack, viewModel, searchViewModel, savedPlacesViewModel, it.idx, it.east, it.west, it.north, it.south, it.query)
        }
    }
}
