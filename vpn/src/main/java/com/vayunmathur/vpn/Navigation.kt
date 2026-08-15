package com.vayunmathur.vpn

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.IconDashboard
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.vpn.platform.VpnViewModel
import com.vayunmathur.vpn.ui.BypassListPage
import com.vayunmathur.vpn.ui.ConfigDetailPage
import com.vayunmathur.vpn.ui.ConfigListPage
import com.vayunmathur.vpn.ui.LoggingPage
import com.vayunmathur.vpn.ui.SettingsPage

private const val TAB_TUNNELS = 0
private const val TAB_LOGGING = 1
private const val TAB_SETTINGS = 2

@Composable
fun Navigation(vm: VpnViewModel) {
    // Land on the Settings tab when opened from the system App Info page.
    val activity = LocalActivity.current
    val startTab = if (activity?.intent?.action == Intent.ACTION_APPLICATION_PREFERENCES) {
        TAB_SETTINGS
    } else {
        TAB_TUNNELS
    }
    val backStack = rememberNavBackStack<Route>(Route.Main(startTab))
    MainNavigation(backStack) {
        entry<Route.Main> { VpnTabs(backStack, vm, it.initialTab) }
        entry<Route.Detail> { ConfigDetailPage(backStack, vm, it.id) }
        entry<Route.BypassList> { BypassListPage(backStack) }
    }
}

/** The three top-level tabs, hosted in a swipeable pager. Detail and BypassList push on top. */
@Composable
private fun VpnTabs(backStack: NavBackStack<Route>, vm: VpnViewModel, initialTab: Int) {
    val pagerState = rememberPagerState(initialPage = initialTab, pageCount = { 3 })
    val tabs = listOf(
        PagerTab("Tunnels", { IconDashboard() }) { ConfigListPage(backStack, vm) },
        PagerTab("Logging", { IconHistory() }) { LoggingPage(backStack, vm) },
        PagerTab("Settings", { IconSettings() }) { SettingsPage(backStack, vm) },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
