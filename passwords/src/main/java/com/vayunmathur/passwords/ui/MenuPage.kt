package com.vayunmathur.passwords.ui
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.platform.MenuUiState
import com.vayunmathur.passwords.platform.PasswordsViewModel

sealed class CredentialItem(override val id: Long) : DatabaseItem {
    class PasswordItem(val password: Password) : CredentialItem(password.id)
    class PasskeyItem(val passkey: Passkey) : CredentialItem(Long.MAX_VALUE - passkey.id)
}

@Composable
fun MenuPage(
    backStack: NavBackStack<Route>,
    viewModel: PasswordsViewModel,
) {
    val now by viewModel.tickerFlow.collectAsState()
    val passwords by viewModel.passwords.collectAsState()
    val passkeys by viewModel.passkeys.collectAsState()
    MenuScreen(backStack, MenuUiState(passwords, passkeys, now), viewModel)
}
