package com.vayunmathur.share

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.TabRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.share.platform.ShareViewModel
import com.vayunmathur.share.ui.ShareReceiveScreen
import com.vayunmathur.share.ui.ShareSendScreen

private enum class ShareTab { Receive, Send }

@Composable
fun Navigation(viewModel: ShareViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Share)
    MainNavigation(backStack) {
        entry<Route.Share> {
            ShareApp(viewModel)
        }
    }
}

@Composable
private fun ShareApp(viewModel: ShareViewModel) {
    var currentTab by remember { mutableStateOf(ShareTab.Receive) }
    val outgoing by viewModel.outgoingUris.collectAsState()
    LaunchedEffect(outgoing) {
        if (outgoing.isNotEmpty()) currentTab = ShareTab.Send
    }
    AppScaffold(title = stringResource(R.string.app_name)) { padding ->
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = currentTab.ordinal) {
                Tab(selected = currentTab == ShareTab.Receive, onClick = { currentTab = ShareTab.Receive }, text = { Text(stringResource(R.string.tab_receive)) })
                Tab(selected = currentTab == ShareTab.Send, onClick = { currentTab = ShareTab.Send }, text = { Text(stringResource(R.string.tab_send)) })
            }
            when (currentTab) {
                ShareTab.Receive -> ShareReceiveScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                ShareTab.Send -> ShareSendScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
            }
        }
    }
}