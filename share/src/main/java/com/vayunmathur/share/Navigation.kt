package com.vayunmathur.share

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.share.platform.ShareViewModel
import com.vayunmathur.share.ui.ShareSendPage

@Composable
fun Navigation(viewModel: ShareViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Share)
    MainNavigation(backStack) {
        entry<Route.Share> {
            ShareSendPage(viewModel)
        }
    }
}
