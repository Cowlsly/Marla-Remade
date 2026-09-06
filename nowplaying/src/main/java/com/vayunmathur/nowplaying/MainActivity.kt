package com.vayunmathur.nowplaying

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.nowplaying.platform.NowPlayingViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: NowPlayingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                PermissionsChecker(
                    permissions = requiredPermissions(),
                    text = getString(R.string.grant_microphone_permission),
                ) {
                    Navigation(viewModel)
                }
            }
        }
    }

    /**
     * The microphone is the whole app, and an always-on microphone service needs a visible
     * notification to be honest about it. POST_NOTIFICATIONS only exists from API 33, and asking
     * for it below that is denied forever, which would wedge the permission wall.
     */
    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
}
