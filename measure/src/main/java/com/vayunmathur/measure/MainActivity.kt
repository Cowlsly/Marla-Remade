package com.vayunmathur.measure

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.measure.platform.MeasureViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MeasureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                Navigation(viewModel)
            }
        }
    }

    // Sensors run only while the app is in front. The compass and level are useless
    // in the background, and the magnetometer is not free to keep polling.
    override fun onStart() {
        super.onStart()
        viewModel.startSensors()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopSensors()
    }
}
