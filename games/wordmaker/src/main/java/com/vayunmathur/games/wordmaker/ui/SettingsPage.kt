package com.vayunmathur.games.wordmaker.ui
import android.Manifest
import android.os.Build
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.ui.DailyReminderSettingsSection
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberPermissionRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.platform.SettingsActions
import com.vayunmathur.games.wordmaker.platform.SettingsUiState
import com.vayunmathur.games.wordmaker.platform.WordMakerViewModel

/** Binds [WordMakerViewModel] to the stateless [SettingsScreen]. */
@Composable
fun SettingsPage(viewModel: WordMakerViewModel, onBack: () -> Unit) {
    val tapToSpell by viewModel.tapToSpell.collectAsState()
    val reminderEnabled by viewModel.reminderEnabled.collectAsState()
    val reminderMinutes by viewModel.reminderMinutesOfDay.collectAsState()

    val requestNotifications = rememberPermissionRequest(Manifest.permission.POST_NOTIFICATIONS)

    SettingsScreen(
        state = SettingsUiState(
            tapToSpell = tapToSpell,
            reminderEnabled = reminderEnabled,
            reminderHour = (reminderMinutes / 60).toInt(),
            reminderMinute = (reminderMinutes % 60).toInt(),
        ),
        actions = object : SettingsActions {
            override fun setTapToSpell(enabled: Boolean) = viewModel.setTapToSpell(enabled)

            override fun setReminderEnabled(enabled: Boolean) {
                // Asked for lazily, on opt-in only, so the screen never blocks on a prompt.
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotifications()
                }
                viewModel.setReminderEnabled(enabled)
            }

            override fun setReminderTime(hour: Int, minute: Int) =
                viewModel.setReminderTime(hour, minute)
        },
        onBack = onBack,
    )
}

/** The settings screen, ViewModel-free so a preview can render it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, onBack: () -> Unit) {
    DetailScaffold(
        title = stringResource(UiR.string.settings),
        onNavigateBack = onBack,
        actions = {
            BackupButtons(datastoreNames = listOf("settings"))
        },
        scrollBehavior = appBarScrollBehavior(),
    ) {
        SettingsSection {
            SettingsSwitchRow(
                title = stringResource(R.string.tap_to_spell),
                supportingText = stringResource(R.string.tap_to_spell_description),
                checked = state.tapToSpell,
                onCheckedChange = { actions.setTapToSpell(it) },
            )
        }
        DailyReminderSettingsSection(
            enabled = state.reminderEnabled,
            hour = state.reminderHour,
            minute = state.reminderMinute,
            onEnabledChange = { actions.setReminderEnabled(it) },
            onTimeChange = { hour, minute -> actions.setReminderTime(hour, minute) },
        )
    }
}
