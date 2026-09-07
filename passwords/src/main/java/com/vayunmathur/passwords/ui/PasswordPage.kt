package com.vayunmathur.passwords.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.domain.passkeysFor
import com.vayunmathur.passwords.platform.PasswordUiState
import com.vayunmathur.passwords.platform.PasswordsViewModel

@Composable
fun PasswordPage(
    backStack: NavBackStack<Route>,
    id: Long,
    viewModel: PasswordsViewModel,
) {
    val password by viewModel.passwordState(id)
    // See MenuPage: not `by`, so the tick does not invalidate the whole screen.
    val ticker = viewModel.tickerFlow.collectAsState()
    val passwords by viewModel.passwords.collectAsState()
    val passkeys by viewModel.passkeys.collectAsState()
    val now = remember(ticker) { { ticker.value } }
    PasswordScreen(
        state = PasswordUiState(
            password = password,
            passkeys = passkeysFor(password, passwords, passkeys),
            now = now,
        ),
        actions = viewModel,
        onBack = { backStack.pop() },
        onEdit = { backStack.add(Route.PasswordEditPage(id)) },
        onOpenPasskey = { backStack.add(Route.PasskeyPage(it)) },
    )
}
