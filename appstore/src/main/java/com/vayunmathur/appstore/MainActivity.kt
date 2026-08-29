package com.vayunmathur.appstore

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.appstore.data.AppStoreDatabaseRepository
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.UpdateCheckWorker
import com.vayunmathur.appstore.ui.AppDetailPage
import com.vayunmathur.appstore.ui.HomePage
import com.vayunmathur.appstore.ui.LibraryPage
import com.vayunmathur.appstore.ui.SearchPage
import com.vayunmathur.appstore.ui.SourcesPage
import com.vayunmathur.appstore.ui.TrustPage
import com.vayunmathur.appstore.ui.UpdatesPage
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.appstore.util.AppStoreViewModelFactory
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.ZoomPage
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var factoryState by mutableStateOf<AppStoreViewModelFactory?>(null)

    /** Package named by a `market://` or listing URL we were launched with. */
    private var externalPkg by mutableStateOf<String?>(null)

    private var vmRef: AppStoreViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // STANDARD includes GTS R1-R4 (needed for Play Store -> play.google.com / android.clients.google.com)
        // + ISRG X1/X2 (F-Droid) + DigiCert G2/G3 (GitHub). Covers all appstore hosts without full system ~140 roots.
        NetworkClient.init(this, TrustBundle.STANDARD)
        enableEdgeToEdge()
        UpdateCheckWorker.schedule(this)

        val repository = AppStoreDatabaseRepository.get(this)
        factoryState = AppStoreViewModelFactory(applicationContext, repository.database)
        handleIntentUrl(intent)

        setContent {
            DynamicTheme {
                OfflineAware {
                    val factory = factoryState
                    if (factory == null) {
                        Box(Modifier.fillMaxSize())
                    } else {
                        val vm: AppStoreViewModel = viewModel(factory = factory)
                        vmRef = vm
                        AppRoot(vm, externalPkg) { externalPkg = null }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Catch installs and uninstalls that completed in the system dialog.
        vmRef?.refreshInstalled()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentUrl(intent)
    }

    private fun handleIntentUrl(intent: Intent?) {
        val data = intent?.dataString ?: return
        val pkg = when {
            data.startsWith("market://") -> ID_PARAM.find(data)?.groupValues?.get(1)
            data.contains("play.google.com") -> ID_PARAM.find(data)?.groupValues?.get(1)
            data.contains("f-droid.org") -> data.substringAfterLast('/').substringBefore('?')
            else -> null
        }
        if (!pkg.isNullOrBlank()) externalPkg = pkg
    }

    private companion object {
        val ID_PARAM = Regex("[?&]id=([^&]+)")
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Main : Route
    @Serializable data object Sources : Route
    @Serializable data object Trust : Route
    @Serializable data object Detail : Route
}

/**
 * Four tabs and three pushed screens.
 *
 * Sources and the trust explainer used to be a tab each, which spent a quarter of the
 * bottom bar on screens opened perhaps once. They are now behind the gear on the home
 * bar, and the freed slot went to search — which was previously a text field wedged above
 * the browse list, so browsing and searching fought over the same screen.
 */
@Composable
private fun AppRoot(
    viewModel: AppStoreViewModel,
    externalPackage: String?,
    onExternalPackageHandled: () -> Unit,
) {
    val backStack = rememberNavBackStack<Route>(Route.Main)

    LaunchedEffect(externalPackage) {
        val pkg = externalPackage ?: return@LaunchedEffect
        viewModel.selectPackage(pkg)
        if (backStack.last() !is Route.Detail) backStack.add(Route.Detail)
        onExternalPackageHandled()
    }

    fun openDetail(app: UnifiedApp) {
        viewModel.selectApp(app)
        if (backStack.last() !is Route.Detail) backStack.add(Route.Detail)
    }

    MainNavigation(backStack) {
        entry<Route.Main> {
            AppTabs(
                viewModel = viewModel,
                onAppClick = ::openDetail,
                onOpenSources = { backStack.add(Route.Sources) },
            )
        }
        entry<Route.Sources> {
            SourcesPage(
                viewModel = viewModel,
                onBack = { backStack.pop() },
                onOpenTrust = { backStack.add(Route.Trust) },
            )
        }
        entry<Route.Trust> {
            TrustPage(
                ownSigningCertificates = viewModel.ownSigningCertificates,
                onBack = { backStack.pop() },
            )
        }
        entry<Route.Detail>(metadata = ZoomPage()) {
            AppDetailPage(
                viewModel = viewModel,
                onBack = {
                    backStack.pop()
                    viewModel.clearSelection()
                },
                onOpenTrust = { backStack.add(Route.Trust) },
            )
        }
    }
}

/**
 * The four bottom-nav tabs, hosted in a swipeable pager (see [TabbedPagerScaffold]).
 * Sources, Trust and Detail are pushed on top of this host as ordinary routes.
 */
@Composable
private fun AppTabs(
    viewModel: AppStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    onOpenSources: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val tabs = listOf(
        PagerTab(stringResource(R.string.nav_home), { IconHome() }) {
            HomePage(
                viewModel = viewModel,
                onAppClick = onAppClick,
                onOpenUpdates = { scope.launch { pagerState.animateScrollToPage(PAGE_UPDATES) } },
                onOpenSources = onOpenSources,
            )
        },
        PagerTab(stringResource(R.string.nav_search), { IconSearch() }) {
            // settledPage, not currentPage: currentPage flips to the nearest page part-way
            // through a swipe or an animated jump, and search grabbing focus at that moment
            // pulls the pager onto itself — so going home -> updates would land on search
            // with the keyboard up.
            SearchPage(
                viewModel = viewModel,
                onAppClick = onAppClick,
                isActive = pagerState.settledPage == PAGE_SEARCH,
            )
        },
        PagerTab(stringResource(R.string.nav_updates), { IconDownload() }) {
            UpdatesPage(viewModel = viewModel, onAppClick = onAppClick)
        },
        PagerTab(stringResource(R.string.nav_library), { IconPackage() }) {
            LibraryPage(viewModel = viewModel, onAppClick = onAppClick)
        },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}

private const val PAGE_SEARCH = 1
private const val PAGE_UPDATES = 2
