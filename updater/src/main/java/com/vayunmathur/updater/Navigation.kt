package com.vayunmathur.updater

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.updater.platform.UpdaterViewModel
import com.vayunmathur.updater.ui.UpdaterScreen

@Composable
fun Navigation(viewModel: UpdaterViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)

    MainNavigation(backStack) {
        entry<Route.Home> {
            UpdaterScreen(
                state = viewModel.state,
                onCheckNow = viewModel::checkNow,
                onAutoInstallChanged = viewModel::setAutoInstall,
                onMeteredAllowedChanged = viewModel::setMeteredAllowed,
            )
        }
    }
}
