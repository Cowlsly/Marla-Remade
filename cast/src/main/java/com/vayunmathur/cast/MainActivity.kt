package com.vayunmathur.cast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.cast.platform.CastViewModel
import com.vayunmathur.cast.platform.CastViewModelFactory
import com.vayunmathur.library.ui.DynamicTheme

/**
 * The app's only Activity.
 *
 * Has to survive the move to a Quick Settings tile whatever else changes: it is the host the
 * screen-capture consent dialog needs, and the tile's long-press target.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: CastViewModel by viewModels { CastViewModelFactory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                Navigation(viewModel)
            }
        }
    }
}
