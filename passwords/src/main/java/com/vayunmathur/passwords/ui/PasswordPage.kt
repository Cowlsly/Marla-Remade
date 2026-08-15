package com.vayunmathur.passwords.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.platform.PasswordUiState
import com.vayunmathur.passwords.platform.PasswordsViewModel

@Composable
fun PasswordPage(
    backStack: NavBackStack<Route>,
    id: Long,
    viewModel: PasswordsViewModel,
) {
    val password by viewModel.passwordState(id)
    val now by viewModel.tickerFlow.collectAsState()
    PasswordScreen(
        state = PasswordUiState(password, now),
        actions = viewModel,
        onBack = { backStack.pop() },
        onEdit = { backStack.add(Route.PasswordEditPage(id)) },
    )
}
