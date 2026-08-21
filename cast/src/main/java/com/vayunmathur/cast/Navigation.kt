package com.vayunmathur.cast

import androidx.compose.runtime.Composable
import com.vayunmathur.cast.platform.CastViewModel
import com.vayunmathur.cast.ui.CastScreen
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: CastViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Cast)
    MainNavigation(backStack) {
        entry<Route.Cast> { CastScreen(viewModel) }
    }
}
