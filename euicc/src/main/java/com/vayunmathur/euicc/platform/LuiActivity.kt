package com.vayunmathur.euicc.platform

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.euicc.Navigation
import com.vayunmathur.library.ui.DynamicTheme

/**
 * The eSIM LUI (Local User Interface) — the entry point the platform opens when the user taps
 * an eSIM option in Settings, rather than when they open this app from the launcher.
 *
 * It exists separately from `MainActivity`, showing the same screen, because of a constraint
 * in how the platform picks an LUI. `EuiccConnector.isValidEuiccComponent` requires the
 * resolving **activity** to be guarded by `BIND_EUICC_SERVICE` — the same rule it applies to
 * the `EuiccService` — and rejects it outright otherwise:
 *
 *     E/EuiccConnector: Package com.vayunmathur.euicc does not require the
 *                       BIND_EUICC_SERVICE permission
 *     W/EuiccUiDispatcher: Could not resolve activity for intent: ...
 *
 * `EuiccUiDispatcherActivity` runs under `Theme.NoDisplay`, so a rejection is invisible: the
 * Settings row simply does nothing. That permission cannot go on `MainActivity`, because
 * `android:permission` constrains the *caller* and the launcher does not hold a signature
 * permission — the launcher icon would stop working. Hence a second, system-only activity,
 * which is the same shape Google's own LPA uses.
 *
 * The result code is deliberately left at the default `RESULT_CANCELED`. The dispatcher sets
 * `FLAG_ACTIVITY_FORWARD_RESULT`, so whatever this returns goes back to the original caller,
 * and for a provisioning request that is the honest answer unless a profile was actually
 * installed.
 */
class LuiActivity : ComponentActivity() {
    private val viewModel: EuiccViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DynamicTheme { Navigation(viewModel) } }
    }
}
