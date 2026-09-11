package com.vayunmathur.safefamily.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.safefamily.platform.SupervisionViewModel

/**
 * The bedtime schedule, reached from Settings > Parental controls.
 *
 * Launched by a dashboard tile rather than a launcher icon - see the manifest. This app still
 * has no entry in the launcher, and these screens are the only UI it owns.
 */
class BedtimeActivity : ComponentActivity() {

    private val viewModel: SupervisionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                BedtimeScreen(
                    state = state,
                    actions = BedtimeActions(
                        onEnabledChange = viewModel::setScheduleEnabled,
                        onStartChange = viewModel::setStart,
                        onEndChange = viewModel::setEnd,
                        onToggleDay = viewModel::toggleDay,
                        onAppBedtimeChange = viewModel::setBedtimeBlocked,
                    ),
                )
            }
        }
    }
}
