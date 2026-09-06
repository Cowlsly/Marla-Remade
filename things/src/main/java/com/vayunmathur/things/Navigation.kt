package com.vayunmathur.things

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.things.platform.BleManager
import com.vayunmathur.things.platform.DeviceController.LinkState
import com.vayunmathur.things.platform.ScaleBleManager
import com.vayunmathur.things.platform.Sex
import com.vayunmathur.things.ui.DevicesPage
import com.vayunmathur.things.ui.HomePage

@Composable
fun Navigation(
    bottlePaired: Boolean,
    bottleLink: LinkState,
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
    onForgetBottle: () -> Unit,
    scalePaired: Boolean,
    scaleLink: LinkState,
    scaleConnectionState: String,
    scaleScanning: Boolean,
    scaleDevices: List<ScaleBleManager.ScaleBleDevice>,
    scaleSex: Sex,
    scaleAge: String,
    scaleHeight: String,
    scaleAthlete: Boolean,
    onScaleScanClick: () -> Unit,
    onScaleDeviceClick: (ScaleBleManager.ScaleBleDevice) -> Unit,
    onForgetScale: () -> Unit,
    onScaleSexChange: (Sex) -> Unit,
    onScaleAgeChange: (String) -> Unit,
    onScaleHeightChange: (String) -> Unit,
    onScaleAthleteChange: (Boolean) -> Unit,
    onHealthConnectClick: () -> Unit,
) {
    // Home is always the root. Devices are powered off most of the time, so gating the landing
    // screen on a live connection would strand the user on a device-picker almost every launch.
    val backStack = rememberNavBackStack<Route>(Route.Home)

    MainNavigation(backStack) {
        entry<Route.Home>(SiblingPage()) {
            HomePage(
                bottlePaired = bottlePaired,
                bottleLink = bottleLink,
                bottleConnectionState = connectionState,
                tempC = tempC,
                tds = tds,
                batteryPct = batteryPct,
                charging = charging,
                volumePct = volumePct,
                lastUpdatedMillis = lastUpdatedMillis,
                scalePaired = scalePaired,
                scaleLink = scaleLink,
                scaleConnectionState = scaleConnectionState,
                scaleSex = scaleSex,
                scaleAge = scaleAge,
                scaleHeight = scaleHeight,
                scaleAthlete = scaleAthlete,
                onScaleSexChange = onScaleSexChange,
                onScaleAgeChange = onScaleAgeChange,
                onScaleHeightChange = onScaleHeightChange,
                onScaleAthleteChange = onScaleAthleteChange,
                onForgetBottle = onForgetBottle,
                onForgetScale = onForgetScale,
                onHealthConnectClick = onHealthConnectClick,
                onOpenDevices = { backStack.add(Route.Devices) },
            )
        }
        entry<Route.Devices>(SiblingPage()) {
            DevicesPage(
                scanning = scanning,
                discoveredDevices = discoveredDevices,
                scaleScanning = scaleScanning,
                scaleDevices = scaleDevices,
                onScanClick = onScanClick,
                onDeviceClick = onDeviceClick,
                onScaleScanClick = onScaleScanClick,
                onScaleDeviceClick = onScaleDeviceClick,
                onNavigateBack = if (backStack.backStack.size > 1) {
                    { backStack.pop() }
                } else {
                    null
                },
            )
        }
    }
}
