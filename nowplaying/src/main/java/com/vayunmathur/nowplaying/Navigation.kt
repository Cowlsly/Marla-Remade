package com.vayunmathur.nowplaying

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.nowplaying.platform.NowPlayingViewModel
import com.vayunmathur.nowplaying.ui.ListenPage

@Composable
fun Navigation(viewModel: NowPlayingViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Listen)
    MainNavigation(backStack) {
        entry<Route.Listen> {
            ListenPage(viewModel)
        }
    }
}
