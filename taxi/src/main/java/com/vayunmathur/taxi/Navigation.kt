package com.vayunmathur.taxi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconMap
import com.vayunmathur.library.ui.IconNavigationArrow
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.taxi.ui.AccountsScreen
import com.vayunmathur.taxi.ui.CurrentRideScreen
import com.vayunmathur.taxi.ui.LyftSignInScreen
import com.vayunmathur.taxi.ui.RideScreen
import com.vayunmathur.taxi.ui.RideTrackingScreen

@Composable
fun Navigation(trackRideId: MutableState<String?>) {
    val backStack = rememberNavBackStack<Route>(Route.Ride)
    val currentPage = backStack.backStack.last()

    LaunchedEffect(trackRideId.value) {
        val id = trackRideId.value ?: return@LaunchedEffect
        backStack.add(Route.RideTracking(id))
        trackRideId.value = null
    }

    val pages = listOf(
        BottomBarItem(stringResource(R.string.nav_ride), Route.Ride) { IconMap() },
        BottomBarItem(stringResource(R.string.nav_current_ride), Route.CurrentRide) { IconNavigationArrow() },
        BottomBarItem(stringResource(R.string.nav_settings), Route.Accounts) { IconSettings() },
    )

    MainNavigation(
        backStack = backStack,
        bottomBar = { BottomNavBar(backStack, pages, currentPage) },
    ) {
        entry<Route.Ride> { RideScreen() }
        entry<Route.CurrentRide> { CurrentRideScreen() }
        entry<Route.Accounts> {
            AccountsScreen(onConnectLyft = { backStack.add(Route.LyftSignIn) })
        }
        entry<Route.LyftSignIn> { LyftSignInScreen(onBack = { backStack.pop() }) }
        entry<Route.RideTracking> { route -> RideTrackingScreen(rideId = route.rideId) }
    }
}
