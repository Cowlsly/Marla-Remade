package com.vayunmathur.passwords

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.biometric.unlockDatabaseWithBiometrics
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.ui.MenuPage
import com.vayunmathur.passwords.ui.PasskeyPage
import com.vayunmathur.passwords.ui.PasswordEditPage
import com.vayunmathur.passwords.ui.PasswordPage
import com.vayunmathur.passwords.ui.SettingsPage
import com.vayunmathur.passwords.sync.KdbxSyncScheduler
import com.vayunmathur.passwords.sync.KdbxSyncSettings
import com.vayunmathur.passwords.util.PasswordsViewModel
import com.vayunmathur.passwords.util.PasswordsViewModelFactory
import kotlinx.serialization.Serializable
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle

class MainActivity : FragmentActivity() {
    private val passwordsViewModel: PasswordsViewModel by viewModels {
        PasswordsViewModelFactory(application, PasswordRepository.get(application))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reduced CA hardening: FIRST_PARTY covers api.vayunmathur.com / data.vayunmathur.com (ISRG+GTS)
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        enableEdgeToEdge()

        unlockDatabaseWithBiometrics(
            activity = this,
            onSuccess = { passphrase ->
                // Sync passphrase to non-auth key so services can access the database.
                // The repo's lazy db will then open via DatabaseHelper (same passphrase).
                DatabaseHelper(this).storePassphrase(passphrase)
                // Force repository (and thus DB) to be created eagerly so the
                // cached databases[PasswordDatabase] entry matches this passphrase.
                PasswordRepository.get(application)
                lifecycleScope.launch {
                    if (KdbxSyncSettings.enabled(this@MainActivity)) {
                        // Re-registering keeps the periodic work alive across app updates.
                        KdbxSyncScheduler.schedulePeriodic(this@MainActivity)
                        KdbxSyncScheduler.syncNow(this@MainActivity)
                    }
                }
                setContent {
                    DynamicTheme {
                        OfflineAware {
                            Navigation(passwordsViewModel, passphrase)
                        }
                    }
                }
            },
            onFailure = { message ->
                message?.let { AppMessages.show(it) }
                finish()
            }
        )
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Menu: Route

    @Serializable
    data class PasswordPage(val id: Long): Route

    @Serializable
    data class PasswordEditPage(val id: Long): Route

    @Serializable
    data class PasskeyPage(val id: Long): Route

    @Serializable
    data object Settings: Route
}


@Composable
fun Navigation(
    passwordsViewModel: PasswordsViewModel,
    passphrase: String,
) {
    val backStack = rememberNavBackStack<Route>(Route.Menu)
    // Land on settings when opened from the system App Info page.
    backStack.openSettingsIfRequested(Route.Settings)
    MainNavigation(backStack) {
        entry<Route.Menu>(metadata = ListPage()) {
            MenuPage(backStack, passwordsViewModel)
        }
        entry<Route.PasswordPage>(metadata = ListDetailPage()) {
            PasswordPage(backStack, it.id, passwordsViewModel)
        }
        entry<Route.PasswordEditPage>(metadata = ListDetailPage()) {
            PasswordEditPage(backStack, it.id, passwordsViewModel)
        }
        entry<Route.PasskeyPage>(metadata = ListDetailPage()) {
            PasskeyPage(backStack, it.id, passwordsViewModel)
        }
        entry<Route.Settings>(metadata = ListDetailPage()) {
            SettingsPage(backStack, passwordsViewModel, passphrase)
        }
    }
}
