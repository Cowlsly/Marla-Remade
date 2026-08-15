package com.vayunmathur.musicbrainz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.musicbrainz.platform.MusicBrainzViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MusicBrainzViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                OfflineAware {
                    Navigation(viewModel)
                }
            }
        }
    }
}
