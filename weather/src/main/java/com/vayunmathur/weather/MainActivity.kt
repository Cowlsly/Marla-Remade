package com.vayunmathur.weather

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.widgets.updateWidgetPreviews
import com.vayunmathur.weather.data.WeatherRepository
import com.vayunmathur.weather.glance.WeatherGlanceWidgetReceiver
import com.vayunmathur.weather.ui.HomePage
import com.vayunmathur.weather.ui.SearchLocationPage
import com.vayunmathur.weather.ui.WeatherMapPage
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.weather.util.WeatherViewModel
import com.vayunmathur.weather.util.WeatherViewModelFactory
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val viewModel: WeatherViewModel by viewModels {
        WeatherViewModelFactory(application, WeatherRepository.get(application))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FIRST_PARTY covers api.vayunmathur.com + api.open-meteo.com (ISRG) + data.vayunmathur.com
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        updateWidgetPreviews(WeatherGlanceWidgetReceiver::class)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                OfflineAware {
                    Navigation(viewModel)
                }
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Home : Route
    @Serializable data object SearchLocation : Route
    @Serializable data class WeatherMap(
        val latitude: Double,
        val longitude: Double,
        val name: String,
        val isoTime: String? = null,
        val metric: String,
    ) : Route
}

@Composable
fun Navigation(viewModel: WeatherViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    MainNavigation(backStack) {
        entry<Route.Home>(metadata = ListPage()) { HomePage(backStack, viewModel) }
        entry<Route.SearchLocation>(metadata = DialogPage()) { SearchLocationPage(backStack, viewModel) }
        entry<Route.WeatherMap>(metadata = ListPage()) {
            WeatherMapPage(backStack, it.latitude, it.longitude, it.name, it.isoTime, it.metric)
        }
    }
}
