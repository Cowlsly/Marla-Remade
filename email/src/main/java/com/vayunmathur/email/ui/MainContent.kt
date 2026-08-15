package com.vayunmathur.email.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.Navigation
import com.vayunmathur.email.Route
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.IntentState

@Composable
fun MainContent(viewModel: EmailViewModel) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle(emptyList())
    val navigationRoute = IntentState.navigationRoute
    val isEmlViewer = navigationRoute is Route.EmlViewer

    if (accounts.isEmpty() && !isEmlViewer) {
        AddAccountScreen(
            onBack = null,
            onAccountAdded = {},
        )
    } else {
        Navigation(viewModel = viewModel)
    }
}
