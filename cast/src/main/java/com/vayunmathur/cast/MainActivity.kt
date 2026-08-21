package com.vayunmathur.cast

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.vayunmathur.cast.platform.CastViewModel
import com.vayunmathur.cast.platform.CastViewModelFactory
import com.vayunmathur.library.ui.DynamicTheme

/**
 * The app's only Activity, from the launcher or from the share sheet.
 *
 * `ACTION_SEND` lands here rather than in a separate popup, unlike `:share`: the device picker
 * *is* the app, so a bottom sheet would show the same list with less room. `singleTop` plus
 * [onNewIntent] is what makes sharing into an already-open Cast work.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: CastViewModel by viewModels { CastViewModelFactory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeSharedUri(intent)
        setContent {
            DynamicTheme {
                Navigation(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeSharedUri(intent)
    }

    /**
     * Take the shared URI as the pending source. It is cast as soon as a receiver is joined, so
     * arriving from the share sheet with nothing connected is a device pick rather than an error.
     */
    private fun consumeSharedUri(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: return
        viewModel.pickLocalFile(uri.toString())
    }
}
