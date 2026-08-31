package com.vayunmathur.vpn

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.vpn.platform.VpnViewModel
import com.vayunmathur.vpn.ui.BypassListPage
import com.vayunmathur.vpn.ui.ConfigDetailPage
import com.vayunmathur.vpn.ui.VpnTabs

@Composable
fun Navigation(vm: VpnViewModel) {
    // Land on the Settings tab when opened from the system App Info page.
    val activity = LocalActivity.current
    val startTab = if (activity?.intent?.action == Intent.ACTION_APPLICATION_PREFERENCES) {
        2
    } else {
        0
    }
    val backStack = rememberNavBackStack<Route>(Route.Main(startTab))
    MainNavigation(backStack) {
        entry<Route.Main> { VpnTabs(backStack, vm, it.initialTab) }
        entry<Route.Detail>(metadata = MorphPage()) { ConfigDetailPage(backStack, vm, it.id) }
        entry<Route.BypassList> { BypassListPage(backStack) }
    }
}
