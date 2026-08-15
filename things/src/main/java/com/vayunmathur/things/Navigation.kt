package com.vayunmathur.things

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.things.platform.BleManager
import com.vayunmathur.things.ui.ThingsApp

@Composable
fun Navigation(
    totalMl: Int,
    goalMl: Int,
    messages: List<String>,
    connectionState: String,
    scanning: Boolean,
    discoveredDevices: List<BleManager.BleDevice>,
    onScanClick: () -> Unit,
    onDeviceClick: (BleManager.BleDevice) -> Unit,
    onDisconnectClick: () -> Unit,
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
                onScanClick = onScanClick,
                onDeviceClick = onDeviceClick,
                onDisconnectClick = onDisconnectClick,
            )
        }
    }
}
