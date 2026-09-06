package com.vayunmathur.things

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.openAppSettings
import com.vayunmathur.things.platform.DeviceController
import com.vayunmathur.things.platform.DeviceService
import com.vayunmathur.things.platform.HealthConnectHelper
import com.vayunmathur.things.ui.PermissionState
import com.vayunmathur.things.ui.PermissionsPage

class MainActivity : ComponentActivity() {

    // Which scan to run once BLE permissions are granted (the launcher can't tell otherwise).
    private var pendingScan: (() -> Unit)? = null

    // Bumped by every permission result so the gate below re-checks. A plain counter rather than
    // caching the answers, because the system is the source of truth and it can change while we
    // are backgrounded.
    private val permissionEpoch = mutableIntStateOf(0)

    // A denied runtime permission that no longer offers a rationale has been permanently denied —
    // but that is also true before the very first ask, so it only tells us anything afterwards.
    private var hasAskedBluetooth = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = pendingScan
        pendingScan = null
        hasAskedBluetooth = true
        // Only the BLE perms gate scanning; POST_NOTIFICATIONS only affects notification visibility.
        val bleGranted = grants[Manifest.permission.BLUETOOTH_SCAN] == true &&
            grants[Manifest.permission.BLUETOOTH_CONNECT] == true
        if (bleGranted && action != null) {
            DeviceService.start(this)
            action()
        }
        permissionEpoch.intValue++
    }

    // Health Connect permission contract (mirrors health app). No-op if HC not available.
    private val healthPermissionLauncher = registerForActivityResult(
        HealthConnectHelper.permissionsContract()
    ) {
        permissionEpoch.intValue++
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

    private fun bluetoothPermissionState(): PermissionState {
        val granted = BLE_REQUIRED.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) return PermissionState.Granted
        val canAsk = BLE_REQUIRED.any { shouldShowRequestPermissionRationale(it) }
        return if (canAsk || !hasAskedBluetooth) PermissionState.Needed else PermissionState.Blocked
    }

    private suspend fun healthConnectPermissionState(): PermissionState {
        if (HealthConnectHelper.availabilityStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            return PermissionState.Blocked
        }
        return try {
            val client = HealthConnectClient.getOrCreate(this)
            if (HealthConnectHelper.hasAllPermissions(client)) {
                PermissionState.Granted
            } else {
                PermissionState.Needed
            }
        } catch (_: Exception) {
            PermissionState.Blocked
        }
    }

    /**
     * Health Connect is part of the platform from Android 14, but below that it is an installable
     * app — so "unavailable" is usually fixable and must not dead-end the gate.
     */
    private fun resolveHealthConnect() {
        val intent = Intent(Intent.ACTION_VIEW, "market://details?id=$HEALTH_CONNECT_PACKAGE".toUri())
        if (intent.resolveActivity(packageManager) != null) startActivity(intent) else openAppSettings(this)
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
                PermissionGate {
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

    /** Shows [content] only once both permissions are in place; otherwise the first-run screen. */
    @Composable
    private fun PermissionGate(content: @Composable () -> Unit) {
        val epoch = permissionEpoch.intValue
        // Permissions can also be changed from system settings while we are backgrounded, which
        // produces no result callback — so re-check whenever we come back to the foreground.
        var resumes by remember { mutableIntStateOf(0) }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resumes++
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val bluetooth = remember(epoch, resumes) { bluetoothPermissionState() }
        var healthConnect by remember { mutableStateOf<PermissionState?>(null) }
        LaunchedEffect(epoch, resumes) { healthConnect = healthConnectPermissionState() }

        // Null until the first async Health Connect check finishes; showing the gate in the
        // meantime would flash it in front of users who granted everything long ago.
        val health = healthConnect ?: return
        if (bluetooth == PermissionState.Granted && health == PermissionState.Granted) {
            content()
        } else {
            PermissionsPage(
                bluetooth = bluetooth,
                healthConnect = health,
                onRequestBluetooth = { permissionLauncher.launch(blePermissions()) },
                onRequestHealthConnect = { requestHealthConnectPermissions() },
                onResolveBluetooth = { openAppSettings(this) },
                onResolveHealthConnect = { resolveHealthConnect() },
            )
        }
    }

    private companion object {
        val BLE_REQUIRED = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
    }
}
