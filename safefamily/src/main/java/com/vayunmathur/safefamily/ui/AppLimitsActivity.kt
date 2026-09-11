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

/** Per-app daily caps, reached from Settings > Parental controls. */
class AppLimitsActivity : ComponentActivity() {

    private val viewModel: SupervisionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                AppLimitsScreen(
                    state = state,
                    actions = AppLimitsActions(onLimitChange = viewModel::setDailyLimit),
                )
            }
        }
    }
}
