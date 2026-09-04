package com.vayunmathur.mapcompare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.mapcompare.ui.ComparatorScreen
import com.vayunmathur.mapcompare.util.MapTileCache

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        // Caching HTTP module so pmtiles:// requests hit library:network
        // with Range, 206 validation and disk cache.
        MapTileCache.install(this)
        enableEdgeToEdge()
        renderWithIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderWithIntent(intent)
    }

    private fun renderWithIntent(intent: android.content.Intent) {
        val archivePath = intent.getStringExtra("archive_path")?.takeIf { it.isNotBlank() }
        // TEMPORARY task-17 pick probe (remove after device verification):
        // --ez pick_probe true + tap the Vulkan side logs placed-label rows.
        val pickProbe = intent.getBooleanExtra("pick_probe", false)
        setContent {
            DynamicTheme {
                ComparatorScreen(archivePath = archivePath, pickProbe = pickProbe)
            }
        }
    }
}
