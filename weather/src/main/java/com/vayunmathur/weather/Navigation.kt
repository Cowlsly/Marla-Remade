package com.vayunmathur.weather

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.weather.platform.WeatherViewModel
import com.vayunmathur.weather.ui.HomePage
import com.vayunmathur.weather.ui.SearchLocationPage
import com.vayunmathur.weather.ui.WeatherMapPage

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
