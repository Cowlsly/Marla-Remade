package com.vayunmathur.clock

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.platform.ClockViewModelFactory
import com.vayunmathur.clock.platform.createNotificationChannels
import com.vayunmathur.clock.ui.InitialPermissionsScreen
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.SpecialAccess
import com.vayunmathur.library.util.DataStoreUtils
class MainActivity : ComponentActivity() {
    private val clockViewModel: ClockViewModel by viewModels {
        ClockViewModelFactory(application, ClockRepository.get(application))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!SpecialAccess.hasExactAlarms(this)) {
            runCatching { SpecialAccess.requestExactAlarms(this) }
        }
        createNotificationChannels(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!notificationManager.canUseFullScreenIntent()) {
                // Direct the user to the settings page to toggle "Allow full screen intents"
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = "package:${packageName}".toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        }
        val ds = DataStoreUtils.getInstance(this)

        val initialRoute = clockViewModel.handleIncomingIntent(intent)

        setContent {
            DynamicTheme {
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyArray()
                }
                var hasPermissions by remember {
                    mutableStateOf(
                        permissions.all {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                        }
                    )
                }
                if (!hasPermissions && permissions.isNotEmpty()) {
                    InitialPermissionsScreen(permissions) { hasPermissions = it }
                } else {
                    Navigation(ds, clockViewModel, initialRoute)
                }
            }
        }
    }
}
