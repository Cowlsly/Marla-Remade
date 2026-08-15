package com.vayunmathur.taxi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.taxi.notifications.RideLiveUpdate

class MainActivity : ComponentActivity() {
    // The ride to open on the tracking screen, set from a notification tap. Held as Compose
    // state so onNewIntent can push a new deep link into the running UI.
    private val trackRideId = mutableStateOf<String?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetworkClient.init(this, TrustBundle.STANDARD)
        enableEdgeToEdge()

        trackRideId.value = intent.trackRideIdOrNull()
        requestNotificationPermissionIfNeeded()

        setContent {
            DynamicTheme {
                Navigation(trackRideId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.trackRideIdOrNull()?.let { trackRideId.value = it }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun Intent.trackRideIdOrNull(): String? =
        getStringExtra(RideLiveUpdate.EXTRA_TRACK_RIDE_ID)?.takeIf { it.isNotBlank() }
}
