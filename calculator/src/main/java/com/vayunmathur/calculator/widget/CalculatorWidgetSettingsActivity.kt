package com.vayunmathur.calculator.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.vayunmathur.calculator.R
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.appBarScrollBehavior
import kotlinx.coroutines.launch

/**
 * The keypad widget's settings, opened by the launcher's configure step.
 *
 * The provider declares `configuration_optional`, so placing a widget never waits on this
 * screen; it is reached by "Settings" on the widget's long-press menu (`reconfigurable`).
 * Settings are written to that one widget's own Glance state, so two copies of the widget can
 * be styled differently, and each takes effect as it is changed rather than on a Done button.
 */
class CalculatorWidgetSettingsActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Reported up front, so backing out of this screen leaves a working widget rather than
        // the launcher discarding the one it has just placed.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                WidgetSettingsScreen()
            }
        }
    }

    @Composable
    private fun WidgetSettingsScreen() {
        var transparent by remember { mutableStateOf(false) }
        // Until the stored value is in, the switch would show a default the widget may not be
        // using, and toggling it would write that wrong value back.
        var loaded by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(appWidgetId) {
            transparent = readTransparent()
            loaded = true
        }

        DetailScaffold(
            title = stringResource(R.string.calculator_widget_label),
            onNavigateBack = { finish() },
            scrollBehavior = appBarScrollBehavior(),
        ) {
            SettingsSection {
                SettingsSwitchRow(
                    title = stringResource(R.string.calculator_widget_transparent),
                    supportingText = stringResource(R.string.calculator_widget_transparent_summary),
                    checked = transparent,
                    enabled = loaded,
                    onCheckedChange = { value ->
                        transparent = value
                        scope.launch { writeTransparent(value) }
                    },
                )
            }
        }
    }

    private suspend fun readTransparent(): Boolean {
        val glanceId = glanceId() ?: return false
        return getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId)[
            CalculatorTransparentKey
        ] == true
    }

    private suspend fun writeTransparent(value: Boolean) {
        val glanceId = glanceId() ?: return
        updateAppWidgetState(this, glanceId) { prefs ->
            prefs[CalculatorTransparentKey] = value
        }
        CalculatorGlanceWidget().update(this, glanceId)
    }

    /** Null while the launcher has an id Glance has not bound a widget to yet. */
    private suspend fun glanceId(): GlanceId? = runCatching {
        GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
    }.getOrNull()
}
