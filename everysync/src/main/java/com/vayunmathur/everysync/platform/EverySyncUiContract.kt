package com.vayunmathur.everysync.util

import com.vayunmathur.everysync.provider.DataType

/**
 * The UI contract between the screens and whatever drives them.
 *
 * Screens take a state value plus an actions interface rather than the ViewModel and the
 * nav back stack, so they can be rendered by a `@Preview` — which is what the store listing
 * images are generated from. It lives in `util` rather than `ui` so the dependency runs one
 * way: `ui` depends on `util`, and the `*Screen(backStack, viewModel)` binders are the only
 * things that know about either.
 *
 * Navigation is part of the actions here rather than a separate set of lambdas because
 * every screen but the accounts list needs at least a back arrow, and the ViewModel has
 * nothing to say about it — the binder implements the whole interface in one place.
 */

/** One row of the accounts list. */
data class AccountRow(
    val accountName: String,
    val providerId: String,
    val syncing: Boolean = false,
    val lastSyncError: String? = null,
    /**
     * Already formatted, because the date/time format follows the user's 12/24-hour
     * setting and so needs a Context, which a preview does not have. Null = never synced.
     */
    val lastSyncedAt: String? = null,
)

/** Everything the accounts list draws. */
data class AccountsUiState(
    val accounts: List<AccountRow> = emptyList(),
)

/** Everything the per-account screen draws. */
data class AccountDetailUiState(
    val accountName: String = "",
    /** Null when the account is gone or not loaded yet, which shows the empty text. */
    val providerId: String? = null,
    val enabledTypes: Set<DataType> = emptySet(),
)

/** Everything the settings screen draws. */
data class SettingsUiState(
    val intervalMinutes: Long = 60L,
    val wifiOnly: Boolean = false,
    val conflictPolicy: String = "",
)

/**
 * Accounts-list callbacks. Every method has a no-op default so a preview can render the
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface AccountsActions {
    fun syncNow(accountName: String) {}
    fun openAccount(accountName: String) {}
    fun openAddAccount() {}
    fun openSettings() {}

    companion object {
        val Noop: AccountsActions = object : AccountsActions {}
    }
}

/** Provider-chooser callbacks. Same no-op-default arrangement as [AccountsActions]. */
interface AddAccountActions {
    fun startOAuth(providerId: String) {}
    fun openDavLogin(providerId: String) {}
    fun addHealthConnectAccount(providerId: String) {}
    fun back() {}

    companion object {
        val Noop: AddAccountActions = object : AddAccountActions {}
    }
}

/** Per-account callbacks. Same no-op-default arrangement as [AccountsActions]. */
interface AccountDetailActions {
    fun toggleType(accountName: String, type: DataType, enabled: Boolean) {}
    fun syncNow(accountName: String) {}
    fun removeAccount(accountName: String) {}
    fun back() {}

    companion object {
        val Noop: AccountDetailActions = object : AccountDetailActions {}
    }
}

/** Settings callbacks. Same no-op-default arrangement as [AccountsActions]. */
interface SettingsActions {
    fun setInterval(minutes: Long) {}
    fun setWifiOnly(value: Boolean) {}
    fun setConflictPolicy(policy: String) {}
    fun back() {}

    companion object {
        val Noop: SettingsActions = object : SettingsActions {}
    }
}
