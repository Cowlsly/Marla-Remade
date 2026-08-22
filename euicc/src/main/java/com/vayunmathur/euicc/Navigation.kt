package com.vayunmathur.euicc

import androidx.compose.runtime.Composable
import com.vayunmathur.euicc.platform.EuiccViewModel
import com.vayunmathur.euicc.ui.EuiccScreen
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: EuiccViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    MainNavigation(backStack) {
        entry<Route.Home> { EuiccScreen(viewModel) }
    }
}
