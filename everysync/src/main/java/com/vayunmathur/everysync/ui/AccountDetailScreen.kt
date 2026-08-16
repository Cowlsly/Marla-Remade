package com.vayunmathur.everysync.ui

import androidx.compose.foundation.layout.fillMaxWidth
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.Route
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.everysync.platform.AccountDetailActions
import com.vayunmathur.everysync.platform.AccountDetailUiState
import com.vayunmathur.everysync.platform.EverySyncViewModel
import com.vayunmathur.library.util.NavBackStack

/** Binds [EverySyncViewModel] and the back stack to the stateless [AccountDetailScreen]. */
@Composable
fun AccountDetailScreen(backStack: NavBackStack<Route>, viewModel: EverySyncViewModel, accountName: String) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val config = accounts.firstOrNull { it.accountName == accountName }
    val actions = remember(viewModel, backStack) {
        object : AccountDetailActions {
            override fun toggleType(accountName: String, type: DataType, enabled: Boolean) =
                viewModel.toggleType(accountName, type, enabled)

            override fun syncNow(accountName: String) = viewModel.syncNow(accountName)

            override fun removeAccount(accountName: String) {
                viewModel.removeAccount(accountName)
                backStack.pop()
            }

            override fun back() = backStack.pop()
        }
    }

    AccountDetailScreen(
        state = AccountDetailUiState(
            accountName = accountName,
            providerId = config?.providerId,
            enabledTypes = config?.enabledTypes.orEmpty(),
        ),
        actions = actions,
    )
}

/**
 * The per-account screen, with no dependency on the ViewModel or the back stack so it can
 * be rendered from a `@Preview` — see `src/screenshotTest`, which is where the store
 * listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(state: AccountDetailUiState, actions: AccountDetailActions) {
    val provider = state.providerId?.let { ProviderRegistry.get(it) }

    DetailScaffold(
        title = state.accountName,
        onNavigateBack = { actions.back() },
    ) {
        if (provider == null) {
            Text(stringResource(R.string.no_accounts))
            return@DetailScaffold
        }
        if (DataType.CONTACTS in provider.capabilities) {
            TypeToggle(R.string.sync_contacts, DataType.CONTACTS in state.enabledTypes) {
                actions.toggleType(state.accountName, DataType.CONTACTS, it)
            }
        }
        if (DataType.CALENDAR in provider.capabilities) {
            TypeToggle(R.string.sync_calendar, DataType.CALENDAR in state.enabledTypes) {
                actions.toggleType(state.accountName, DataType.CALENDAR, it)
            }
        }
        if (DataType.HEALTH in provider.capabilities) {
            TypeToggle(R.string.sync_health, DataType.HEALTH in state.enabledTypes) {
                actions.toggleType(state.accountName, DataType.HEALTH, it)
            }
        }

        Button(
            onClick = { actions.syncNow(state.accountName) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.sync_now)) }

        OutlinedButton(
            onClick = { actions.removeAccount(state.accountName) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.remove_account)) }
    }
}

@Composable
private fun TypeToggle(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsSwitchRow(
        title = stringResource(labelRes),
        checked = checked,
        onCheckedChange = onChange,
    )
}
