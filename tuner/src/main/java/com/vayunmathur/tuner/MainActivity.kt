package com.vayunmathur.tuner

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.tuner.platform.TunerViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: TunerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                // In front of the navigation graph rather than around the tabs: the microphone
                // opens by itself as soon as the tabs compose, so the grant has to be settled
                // before any of the app is on screen.
                PermissionsChecker(
                    permissions = arrayOf(Manifest.permission.RECORD_AUDIO),
                    text = stringResource(R.string.permission_title),
                ) {
                    Navigation(viewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Pairs with onStop below: the microphone is released while the app is away and reopened
        // on return. No-op until the permission gate lets the content compose.
        viewModel.onForeground()
    }

    override fun onStop() {
        super.onStop()
        // Nothing here needs to keep listening in the background, and an open microphone is the
        // one part of this app with a real battery cost.
        viewModel.onBackground()
    }
}
