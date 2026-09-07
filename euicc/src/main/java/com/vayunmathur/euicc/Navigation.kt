package com.vayunmathur.euicc

import androidx.compose.runtime.Composable
import com.vayunmathur.euicc.platform.EuiccViewModel
import com.vayunmathur.euicc.ui.ActivationCodeScreen
import com.vayunmathur.euicc.ui.AddSimScreen
import com.vayunmathur.euicc.ui.DeviceInfoScreen
import com.vayunmathur.euicc.ui.DownloadScreen
import com.vayunmathur.euicc.ui.EuiccHomeScreen
import com.vayunmathur.euicc.ui.ProfileDetailScreen
import com.vayunmathur.euicc.ui.QrScannerScreen
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: EuiccViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)

    /**
     * Leaving the activation flow: drop every step of it at once rather than popping back
     * through the scanner and the code entry, and reset the download so re-entering starts
     * clean instead of re-showing the last result.
     */
    fun finishActivation() {
        viewModel.clearDownload()
        backStack.reset(Route.Home)
    }

    MainNavigation(backStack) {
        entry<Route.Home> {
            EuiccHomeScreen(
                state = viewModel.state,
                backStack = backStack,
                onReload = viewModel::reload,
                onAddSim = { backStack.add(Route.AddSim) },
            )
        }
        entry<Route.ProfileDetail> { route ->
            ProfileDetailScreen(
                iccid = route.iccid,
                state = viewModel.state,
                backStack = backStack,
                onEnable = { viewModel.enable(it.iccid) },
                onDisable = { viewModel.disable(it.iccid) },
                onErase = { viewModel.delete(it.iccid) },
                onRename = { profile, name -> viewModel.rename(profile.iccid, name) },
            )
        }
        entry<Route.DeviceInfo> {
            DeviceInfoScreen(
                state = viewModel.state,
                backStack = backStack,
                onRemoveNotification = viewModel::removeNotification,
            )
        }
        entry<Route.AddSim> { AddSimScreen(backStack = backStack) }
        entry<Route.ScanQr> {
            QrScannerScreen(
                onResult = { backStack.add(Route.Download(it)) },
                onCancel = { backStack.pop() },
            )
        }
        entry<Route.ActivationCode> { ActivationCodeScreen(backStack = backStack) }
        entry<Route.Download> { route ->
            DownloadScreen(
                activationCode = route.activationCode,
                state = viewModel.download,
                backStack = backStack,
                onStart = viewModel::startDownload,
                onDone = ::finishActivation,
            )
        }
    }
}
