package com.vayunmathur.networklocation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

/**
 * Status screen for the system network-location + geocoder provider. This app is primarily
 * headless (two bound framework services); this screen just reports state and offers the
 * offline-database download. It has no launcher entry — Settings links to it from
 * Location -> Location services.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp)
                    ) {
                        Text(
                            "Network Location & Geocoder",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            "Provides the system network-location provider (via the " +
                                "GrapheneOS proxy) and an on-device, offline geocoder. " +
                                "Always on — there is no disable switch.",
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OfflineDatabaseSection(Modifier.padding(top = 24.dp))
                    }
                }
            }
        }
    }
}
