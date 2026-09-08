package com.vayunmathur.updater

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.updater.notifications.UpdateNotifications
import com.vayunmathur.updater.platform.UpdateCheckWorker
import com.vayunmathur.updater.platform.UpdaterViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: UpdaterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Scheduling here as well as from the boot receiver covers a first launch, where the
        // app has never been through a boot in a non-stopped state and so has never received
        // BOOT_COMPLETED. Both paths use ExistingPeriodicWorkPolicy.KEEP, so this is a no-op
        // once the work is already enqueued.
        UpdateNotifications.ensureChannels(this)
        UpdateCheckWorker.schedule(this)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                Navigation(viewModel)
            }
        }
    }
}
