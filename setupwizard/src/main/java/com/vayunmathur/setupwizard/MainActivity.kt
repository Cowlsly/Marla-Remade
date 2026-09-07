package com.vayunmathur.setupwizard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.setupwizard.platform.DebugFlags
import com.vayunmathur.setupwizard.platform.SetupViewModel

/**
 * The whole wizard.
 *
 * One Activity rather than the nine the upstream app used, because the platform only resolves
 * one of them: this is the HOME activity at priority 999 that wins while the device is
 * unprovisioned, and the other eight were reachable only from it. The steps are destinations
 * in [Navigation] now.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SetupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // HOME resolution does not stop pointing here the moment setup is done - the package is
        // disabled for that, and until it is, or if it is ever re-enabled, launching lands
        // back on step one. Bail straight to completion instead of running the flow again.
        if (viewModel.system.isUserSetupComplete() &&
            DebugFlags.getBool(this, "allowLaunchAfterSetupCompleted") != true
        ) {
            viewModel.finishSetup(this, disableOemUnlocking = false)
            return
        }

        enableEdgeToEdge()
        setContent { DynamicTheme { Navigation(viewModel) } }
    }
}
