package com.vayunmathur.maps

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.downloadservice.InitialDownloadChecker
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.maps.ui.DownloadedMapsPage
import com.vayunmathur.maps.ui.MapPage
import com.vayunmathur.maps.ui.SavedPlacesPage
import com.vayunmathur.maps.ui.SearchPage
import com.vayunmathur.maps.ui.settings.MapSettingsPage
import com.vayunmathur.maps.data.MapPreferences
import com.vayunmathur.maps.data.ThemeMode
import com.vayunmathur.maps.util.MapTileCache
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.MapsZonesViewModel
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.GooglePoiMapViewModel
import com.vayunmathur.maps.util.MapSettingsViewModel
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import kotlinx.serialization.Serializable
import org.maplibre.android.log.Logger
import java.io.File

class MainActivity : ComponentActivity() {

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
//
//        runBlocking {
//            ds.setBoolean("dbSetupComplete", false)
//            ds.setBoolean("done_metadata.bin", false)
//            ds.setBoolean("done_road_names.bin", false)
//            File(getExternalFilesDir(null), "metadata.bin").delete()
//            File(getExternalFilesDir(null), "road_names.bin").delete()
//        }

        setContent {
            val themeMode by ds.stringFlow(MapPreferences.KEY_THEME_MODE)
                .collectAsState(initial = ds.getString(MapPreferences.KEY_THEME_MODE))
            DynamicTheme(darkTheme = ThemeMode.from(themeMode).darkOverride) {
                InitialDownloadChecker(ds, listOf(
                    Triple("https://data.vayunmathur.com/metadata.bin", "metadata.bin", getString(R.string.downloading_navigation_metadata)),
                    Triple("https://data.vayunmathur.com/road_names.bin", "road_names.bin", getString(R.string.downloading_road_data)),
                    Triple("https://data.vayunmathur.com/intermediate.bin", "intermediate.bin", getString(R.string.downloading_road_data)),
                    Triple("https://data.vayunmathur.com/nodes.bin", "nodes.bin", getString(R.string.downloading_road_data)),
                    Triple("https://data.vayunmathur.com/edges.bin", "edges.bin", getString(R.string.downloading_road_data)),
                    Triple("https://data.vayunmathur.com/transit_voyages.bin", "transit_voyages.bin", getString(R.string.downloading_road_data)),
                    Triple("https://data.vayunmathur.com/transit_attributes.bin", "transit_attributes.bin", getString(R.string.downloading_road_data))
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
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object MapPage: Route
    @Serializable
    data object DownloadedMapsPage: Route
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
    zonesViewModel: MapsZonesViewModel = viewModel(),
    savedPlacesViewModel: SavedPlacesViewModel = viewModel(),
    poiViewModel: GooglePoiMapViewModel = viewModel(),
    settingsViewModel: MapSettingsViewModel = viewModel(),
) {
    val backStack = rememberNavBackStack<Route>(Route.MapPage)
    MainNavigation(backStack) {
        entry<Route.MapPage> {
            MapPage(backStack, viewModel, zonesViewModel, savedPlacesViewModel, poiViewModel, searchViewModel, settingsViewModel)
        }
        entry<Route.DownloadedMapsPage> {
            DownloadedMapsPage(backStack, zonesViewModel)
        }
        entry<Route.SettingsPage> {
            MapSettingsPage(backStack, settingsViewModel)
        }
        entry<Route.SavedPlacesPage> {
            SavedPlacesPage(backStack, savedPlacesViewModel)
        }
        entry<Route.SearchPage> {
            SearchPage(backStack, viewModel, searchViewModel, it.idx, it.east, it.west, it.north, it.south, it.query)
        }
    }
}
