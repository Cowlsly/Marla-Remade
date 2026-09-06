package com.vayunmathur.tuner

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.tuner.platform.TunerViewModel
import com.vayunmathur.tuner.ui.TunerTabs

@Composable
fun Navigation(viewModel: TunerViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Tuner)
    MainNavigation(backStack) {
        entry<Route.Tuner> {
            TunerTabs(viewModel)
        }
    }
}
