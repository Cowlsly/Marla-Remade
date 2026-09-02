package com.vayunmathur.things

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.things.platform.BleManager
import com.vayunmathur.things.platform.ScaleBleManager
import com.vayunmathur.things.platform.Sex
import com.vayunmathur.things.ui.DevicesPage
import com.vayunmathur.things.ui.HomePage

@Composable
fun Navigation(
    connectionState: String,
    scanning: Boolean,
    discoveredDevices: List<BleManager.BleDevice>,
    tempC: Int?,
    tds: Int?,
    batteryPct: Int?,
    charging: Boolean,
    volumePct: Int?,
    lastUpdatedMillis: Long?,
    onScanClick: () -> Unit,
    onDeviceClick: (BleManager.BleDevice) -> Unit,
    onDisconnectClick: () -> Unit,
    scaleConnectionState: String,
    scaleScanning: Boolean,
    scaleDevices: List<ScaleBleManager.ScaleBleDevice>,
    scaleSex: Sex,
    scaleAge: String,
    scaleHeight: String,
    scaleAthlete: Boolean,
    onScaleScanClick: () -> Unit,
    onScaleDeviceClick: (ScaleBleManager.ScaleBleDevice) -> Unit,
    onScaleDisconnectClick: () -> Unit,
    onScaleSexChange: (Sex) -> Unit,
    onScaleAgeChange: (String) -> Unit,
    onScaleHeightChange: (String) -> Unit,
    onScaleAthleteChange: (Boolean) -> Unit,
    onHealthConnectClick: () -> Unit,
) {
    val backStack = rememberNavBackStack<Route>(Route.Devices)

    // Connection is derived from the managers' status strings (see BleManager/ScaleBleManager);
    // there is no separate boolean link-state to read.
    val bottleConnected = connectionState == "Connected"
    val scaleConnected = scaleConnectionState.startsWith("Scale: ") ||
        scaleConnectionState.contains("step on") ||
        scaleConnectionState.startsWith("Weighing")
    val anyConnected = bottleConnected || scaleConnected

    // On the first device connecting, swap Devices out for Home so there is no back to an empty
    // Devices screen. Only fires while sitting on Devices, so opening Devices manually later stays.
    var wasConnected by remember { mutableStateOf(anyConnected) }
    LaunchedEffect(anyConnected) {
        if (anyConnected && !wasConnected && backStack.last() == Route.Devices) {
            backStack.reset(Route.Home)
        }
        wasConnected = anyConnected
    }

    MainNavigation(backStack) {
        entry<Route.Home>(SiblingPage()) {
            HomePage(
                bottleConnected = bottleConnected,
                tempC = tempC,
                tds = tds,
                batteryPct = batteryPct,
                charging = charging,
                volumePct = volumePct,
                lastUpdatedMillis = lastUpdatedMillis,
                scaleConnected = scaleConnected,
                scaleConnectionState = scaleConnectionState,
                scaleSex = scaleSex,
                scaleAge = scaleAge,
                scaleHeight = scaleHeight,
                scaleAthlete = scaleAthlete,
                onScaleSexChange = onScaleSexChange,
                onScaleAgeChange = onScaleAgeChange,
                onScaleHeightChange = onScaleHeightChange,
                onScaleAthleteChange = onScaleAthleteChange,
                onOpenDevices = { backStack.add(Route.Devices) },
            )
        }
        entry<Route.Devices>(SiblingPage()) {
            DevicesPage(
                connectionState = connectionState,
                scanning = scanning,
                discoveredDevices = discoveredDevices,
                onScanClick = onScanClick,
                onDeviceClick = onDeviceClick,
                onDisconnectClick = onDisconnectClick,
                scaleConnectionState = scaleConnectionState,
                scaleScanning = scaleScanning,
                scaleDevices = scaleDevices,
                onScaleScanClick = onScaleScanClick,
                onScaleDeviceClick = onScaleDeviceClick,
                onScaleDisconnectClick = onScaleDisconnectClick,
                onHealthConnectClick = onHealthConnectClick,
                onNavigateBack = if (backStack.backStack.size > 1) {
                    { backStack.pop() }
                } else {
                    null
                },
            )
        }
    }
}
