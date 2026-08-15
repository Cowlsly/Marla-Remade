package com.vayunmathur.vpn

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.ui.IconDashboard
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.vpn.data.ConnectionLogDao
import com.vayunmathur.vpn.data.VpnConfigDao
import com.vayunmathur.vpn.data.VpnDatabase
import com.vayunmathur.vpn.ui.BypassListPage
import com.vayunmathur.vpn.ui.ConfigDetailPage
import com.vayunmathur.vpn.ui.ConfigListPage
import com.vayunmathur.vpn.ui.LoggingPage
import com.vayunmathur.vpn.ui.SettingsPage
import com.vayunmathur.vpn.util.VpnNative
import com.vayunmathur.vpn.util.VpnViewModel
import com.vayunmathur.vpn.util.VpnViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private lateinit var configDao: VpnConfigDao
    private lateinit var logDao: ConnectionLogDao
    private val vm: VpnViewModel by viewModels {
        VpnViewModelFactory(application, configDao, logDao)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SYSTEM: user-supplied VPN endpoint dynamic host, cannot pin
        NetworkClient.init(this, TrustBundle.SYSTEM)
        enableEdgeToEdge()

        val ready = mutableStateOf(false)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { VpnNative.init() }
            val db = VpnDatabase.get(this@MainActivity)
            configDao = db.vpnConfigDao()
            logDao = db.connectionLogDao()
            withContext(Dispatchers.Main) {
                ready.value = true
                handleIntent(intent)
            }
        }

        setContent {
            DynamicTheme {
                OfflineAware {
                    if (ready.value) Navigation(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Accept VIEW intents for .conf files from Files / Downloads etc.
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            vm.importFromUri(this, uri)
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data class Main(val initialTab: Int = 0) : Route
    @Serializable
    data class Detail(val id: Long) : Route
    @Serializable
    data object BypassList : Route
}

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
