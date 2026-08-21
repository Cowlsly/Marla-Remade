package com.vayunmathur.share

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.share.platform.ShareViewModel
import com.vayunmathur.share.ui.ShareSendScreen

@Composable
fun Navigation(viewModel: ShareViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Share)
    MainNavigation(backStack) {
        entry<Route.Share> {
            ShareApp(viewModel)
        }
    }
}

/**
 * The whole in-app surface: sending.
 *
 * Receiving has no screen. It is driven by notifications and turned on and off from a Quick
 * Settings tile, so it works when the app has never been opened.
 */
@Composable
private fun ShareApp(viewModel: ShareViewModel) {
    AppScaffold(title = stringResource(R.string.app_name), scrollBehavior = appBarScrollBehavior()) { padding ->
        ShareSendScreen(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}
