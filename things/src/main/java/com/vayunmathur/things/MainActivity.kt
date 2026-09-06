package com.vayunmathur.things

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.things.platform.DeviceController
import com.vayunmathur.things.platform.DeviceService
import com.vayunmathur.things.platform.HealthConnectHelper

class MainActivity : ComponentActivity() {

    // Which scan to run once BLE permissions are granted (the launcher can't tell otherwise).
    private var pendingScan: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = pendingScan
        pendingScan = null
        // Only the BLE perms gate scanning; POST_NOTIFICATIONS only affects notification visibility.
        val bleGranted = grants[Manifest.permission.BLUETOOTH_SCAN] == true &&
            grants[Manifest.permission.BLUETOOTH_CONNECT] == true
        if (bleGranted && action != null) {
            DeviceService.start(this)
            action()
        }
    }

    // Health Connect permission contract (mirrors health app). No-op if HC not available.
    private val healthPermissionLauncher = registerForActivityResult(
        HealthConnectHelper.permissionsContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectHelper.requiredPermissions)) {
            // Permissions granted — next writes will succeed.
        }
    }

    private fun requestScan(scan: () -> Unit) {
        pendingScan = scan
        permissionLauncher.launch(blePermissions())
    }

    private fun blePermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.toTypedArray()
    }

    private fun requestHealthConnectPermissions() {
        if (!DeviceController.isHealthConnectAvailable()) return
        healthPermissionLauncher.launch(HealthConnectHelper.requiredPermissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DeviceController.init(applicationContext)
        // The service keeps the link alive in the background and drives auto-connect; in the
        // foreground reconnect still works because init() already built the managers.
        if (DeviceController.hasRememberedDevice()) DeviceService.start(this)
        setContent {
            DynamicTheme {
                Navigation(
                    onScanClick = { requestScan { DeviceController.startBottleScan() } },
                    onDeviceClick = {
                        DeviceService.start(this)
                        DeviceController.connectBottle(it.address)
                    },
                    onForgetBottle = {
                        DeviceController.disconnectBottle()
                        if (!DeviceController.hasRememberedDevice()) DeviceService.stop(this)
                    },
                    onScaleScanClick = { requestScan { DeviceController.startScaleScan() } },
                    onScaleDeviceClick = {
                        DeviceService.start(this)
                        DeviceController.connectScale(it.address)
                    },
                    onForgetScale = {
                        DeviceController.disconnectScale()
                        if (!DeviceController.hasRememberedDevice()) DeviceService.stop(this)
                    },
                    onHealthConnectClick = { requestHealthConnectPermissions() },
                )
            }
        }
    }
}
