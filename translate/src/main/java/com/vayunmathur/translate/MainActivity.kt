package com.vayunmathur.translate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.downloadservice.InitialModelDownloadChecker
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.translate.platform.Small100Model
import com.vayunmathur.translate.platform.TranslateViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: TranslateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        enableEdgeToEdge()
        val initialText = processTextFromIntent(intent)
        val ds = DataStoreUtils.getInstance(this)
        setContent {
            DynamicTheme {
                InitialModelDownloadChecker(ds, Small100Model.FILES) {
                    Navigation(viewModel, initialText)
                }
            }
        }
    }

    private fun processTextFromIntent(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_PROCESS_TEXT) return ""
        return intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
    }
}
