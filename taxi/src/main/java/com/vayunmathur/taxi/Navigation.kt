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
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.taxi.data.BookingTrip
import com.vayunmathur.taxi.ui.AccountsScreen
import com.vayunmathur.taxi.ui.CurrentRideScreen
import com.vayunmathur.taxi.ui.LyftSignInScreen
import com.vayunmathur.taxi.ui.RideScreen
import com.vayunmathur.taxi.ui.RideTrackingScreen

@Composable
fun Navigation(trackRideId: MutableState<String?>, bookingTrip: MutableState<BookingTrip?>) {
    val backStack = rememberNavBackStack<Route>(Route.Ride)
    val currentPage = backStack.backStack.last()

    LaunchedEffect(trackRideId.value) {
        val id = trackRideId.value ?: return@LaunchedEffect
        backStack.add(Route.RideTracking(id))
        trackRideId.value = null
    }

    // A `taxi://book` trip pre-fills the ride screen: make sure that tab is the one shown, then
    // RideScreen consumes the trip. (Cold-start already opens on Route.Ride.)
    LaunchedEffect(bookingTrip.value) {
        if (bookingTrip.value == null) return@LaunchedEffect
        if (backStack.backStack.lastOrNull() != Route.Ride) backStack.add(Route.Ride)
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
        entry<Route.Ride>(metadata = SiblingPage()) { RideScreen(bookingTrip) }
        entry<Route.CurrentRide>(metadata = SiblingPage()) { CurrentRideScreen() }
        entry<Route.Accounts>(metadata = SiblingPage()) {
            AccountsScreen(onConnectLyft = { backStack.add(Route.LyftSignIn) })
        }
        entry<Route.LyftSignIn> { LyftSignInScreen(onBack = { backStack.pop() }) }
        entry<Route.RideTracking> { route -> RideTrackingScreen(rideId = route.rideId) }
    }
}
