package com.vayunmathur.auto

import androidx.compose.runtime.Composable
import com.vayunmathur.auto.platform.AutoViewModel
import com.vayunmathur.auto.ui.AutoScreen
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: AutoViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    MainNavigation(backStack) {
        entry<Route.Home> {
            AutoScreen(state = viewModel.state)
        }
    }
}
