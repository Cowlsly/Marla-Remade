package com.vayunmathur.everysync.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.everysync.data.Settings
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.platform.AccountDetailActions
import com.vayunmathur.everysync.platform.AccountDetailUiState
import com.vayunmathur.everysync.platform.AccountRow
import com.vayunmathur.everysync.platform.AccountsActions
import com.vayunmathur.everysync.platform.AccountsUiState
import com.vayunmathur.everysync.platform.AddAccountActions
import com.vayunmathur.everysync.platform.SettingsActions
import com.vayunmathur.everysync.platform.SettingsUiState
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:everysync`. See `common-conventions-preview-metadata`.
 *
 * The four screens tell the whole story: what is syncing, everything you can sync from,
 * what each account is allowed to touch, and how often it runs.
 *
 * Everything here is a literal — no account store, no AccountManager, no network — which
 * is also what makes the images reproducible from a clean checkout. In particular the
 * "last synced" stamps are fixed strings rather than a formatted `System.currentTimeMillis()`,
 * so re-running `:everysync:metadata` tomorrow produces the same PNGs.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-accounts", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Accounts() {
        DynamicTheme(darkTheme = true) {
            AccountsScreen(
                state = AccountsUiState(
                    accounts = listOf(
                        AccountRow(
                            accountName = "jane@gmail.com (Google)",
                            providerId = "google",
                            lastSyncedAt = "3/12/25 9:41 AM",
                        ),
                        AccountRow(
                            accountName = "jane@icloud.com (Apple / iCloud)",
                            providerId = "icloud",
                            syncing = true,
                        ),
                        AccountRow(
                            accountName = "jane (CalDAV server)",
                            providerId = "caldav",
                            lastSyncedAt = "3/12/25 9:05 AM",
                        ),
                        AccountRow(
                            accountName = "jane@gmail.com (Google Health)",
                            providerId = "google_health",
                            lastSyncedAt = "3/12/25 8:15 AM",
                        ),
                    ),
                ),
                actions = AccountsActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-add-account", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2AddAccount() {
        DynamicTheme(darkTheme = true) {
            AddAccountScreen(actions = AddAccountActions.Noop)
        }
    }

    @PreviewTest
    @Preview(name = "3-account", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Account() {
        DynamicTheme(darkTheme = true) {
            AccountDetailScreen(
                state = AccountDetailUiState(
                    accountName = "jane@gmail.com (Google)",
                    providerId = "google",
                    enabledTypes = setOf(DataType.CONTACTS, DataType.CALENDAR),
                ),
                actions = AccountDetailActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-settings", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4Settings() {
        DynamicTheme(darkTheme = true) {
            SettingsScreen(
                state = SettingsUiState(
                    intervalMinutes = 60L,
                    wifiOnly = true,
                    conflictPolicy = Settings.CONFLICT_LWW,
                ),
                actions = SettingsActions.Noop,
            )
        }
    }
}
