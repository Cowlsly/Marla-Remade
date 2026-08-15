package com.vayunmathur.everysync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.vayunmathur.everysync.platform.EverySyncViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.OfflineAware

class MainActivity : ComponentActivity() {
    private val viewModel: EverySyncViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                // Paint the theme background behind everything (incl. the status bar strip the
                // offline banner leaves transparent) so no white window background shows through
                // when offline. See issue #488.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    OfflineAware {
                        Navigation(viewModel)
                    }
                }
            }
        }
    }
}
