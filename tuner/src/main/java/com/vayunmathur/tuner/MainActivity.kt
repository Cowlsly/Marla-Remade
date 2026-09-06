package com.vayunmathur.tuner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.tuner.platform.TunerViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: TunerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                Navigation(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Pairs with onStop below: the microphone is released while the app is away and reopened
        // on return, but only if the user had it listening.
        viewModel.onForeground()
    }

    override fun onStop() {
        super.onStop()
        // Nothing here needs to keep listening in the background, and an open microphone is the
        // one part of this app with a real battery cost.
        viewModel.onBackground()
    }
}
