package com.vayunmathur.vpn.ui

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.IconDashboard
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.platform.VpnViewModel

/** The three top-level tabs, hosted in a swipeable pager. Detail and BypassList push on top. */
@Composable
fun VpnTabs(backStack: NavBackStack<Route>, vm: VpnViewModel, initialTab: Int) {
    val pagerState = rememberPagerState(initialPage = initialTab, pageCount = { 3 })
    val tabs = listOf(
        PagerTab("Tunnels", { IconDashboard() }) { ConfigListPage(backStack, vm) },
        PagerTab("Logging", { IconHistory() }) { LoggingPage(backStack, vm) },
        PagerTab("Settings", { IconSettings() }) { SettingsPage(backStack, vm) },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
