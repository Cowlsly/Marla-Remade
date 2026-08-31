package com.vayunmathur.things

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.things.platform.BleManager
import com.vayunmathur.things.platform.BodyMetrics
import com.vayunmathur.things.platform.ScaleBleManager
import com.vayunmathur.things.platform.Sex
import com.vayunmathur.things.ui.ThingsApp

@Composable
fun Navigation(
    totalMl: Int,
    goalMl: Int,
    messages: List<String>,
    connectionState: String,
    scanning: Boolean,
    discoveredDevices: List<BleManager.BleDevice>,
    tempC: Int?,
    tds: Int?,
    batteryPct: Int?,
    charging: Boolean,
    onScanClick: () -> Unit,
    onDeviceClick: (BleManager.BleDevice) -> Unit,
    onDisconnectClick: () -> Unit,
    scaleWeight: Double?,
    scaleRealtimeWeight: Double?,
    scaleR50: Int?,
    scaleConnectionState: String,
    scaleScanning: Boolean,
    scaleDevices: List<ScaleBleManager.ScaleBleDevice>,
    scaleMetrics: BodyMetrics?,
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
) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    MainNavigation(backStack) {
        entry<Route.Home> {
            ThingsApp(
                totalMl = totalMl,
                goalMl = goalMl,
                messages = messages,
                connectionState = connectionState,
                scanning = scanning,
                discoveredDevices = discoveredDevices,
                tempC = tempC,
                tds = tds,
                batteryPct = batteryPct,
                charging = charging,
                onScanClick = onScanClick,
                onDeviceClick = onDeviceClick,
                onDisconnectClick = onDisconnectClick,
                scaleWeight = scaleWeight,
                scaleRealtimeWeight = scaleRealtimeWeight,
                scaleR50 = scaleR50,
                scaleConnectionState = scaleConnectionState,
                scaleScanning = scaleScanning,
                scaleDevices = scaleDevices,
                scaleMetrics = scaleMetrics,
                scaleSex = scaleSex,
                scaleAge = scaleAge,
                scaleHeight = scaleHeight,
                scaleAthlete = scaleAthlete,
                onScaleScanClick = onScaleScanClick,
                onScaleDeviceClick = onScaleDeviceClick,
                onScaleDisconnectClick = onScaleDisconnectClick,
                onScaleSexChange = onScaleSexChange,
                onScaleAgeChange = onScaleAgeChange,
                onScaleHeightChange = onScaleHeightChange,
                onScaleAthleteChange = onScaleAthleteChange,
            )
        }
    }
}
