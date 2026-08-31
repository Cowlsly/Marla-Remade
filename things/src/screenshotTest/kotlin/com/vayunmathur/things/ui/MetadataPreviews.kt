package com.vayunmathur.things.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.things.platform.BleManager
import com.vayunmathur.things.platform.BodyComposition
import com.vayunmathur.things.platform.ScaleMeasurement
import com.vayunmathur.things.platform.ScaleProfile
import com.vayunmathur.things.platform.Sex

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:things`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-connected", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Connected() {
        val metrics = BodyComposition.calculate(
            ScaleProfile(sex = Sex.Male, age = 30, heightCm = 178.0, athlete = false),
            ScaleMeasurement(weightKg = 78.0, resistance50 = 520, resistance500 = 480)
        )
        DynamicTheme(darkTheme = true) {
            ThingsApp(
                totalMl = 1450,
                goalMl = 2000,
                messages = listOf(
                    "12:40  +250 ml",
                    "11:05  +500 ml",
                    "09:20  +350 ml",
                    "08:00  +350 ml",
                ),
                connectionState = "Connected",
                scanning = false,
                discoveredDevices = emptyList(),
                tempC = 22,
                tds = 85,
                batteryPct = 75,
                charging = false,
                onScanClick = {},
                onDeviceClick = {},
                onDisconnectClick = {},
                scaleWeight = 78.0,
                scaleRealtimeWeight = null,
                scaleR50 = 520,
                scaleConnectionState = "Scale: 78.0 kg",
                scaleScanning = false,
                scaleDevices = emptyList(),
                scaleMetrics = metrics,
                scaleSex = Sex.Male,
                scaleAge = "30",
                scaleHeight = "178",
                scaleAthlete = false,
                onScaleScanClick = {},
                onScaleDeviceClick = {},
                onScaleDisconnectClick = {},
                onScaleSexChange = {},
                onScaleAgeChange = {},
                onScaleHeightChange = {},
                onScaleAthleteChange = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-scanning", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Scanning() {
        DynamicTheme(darkTheme = true) {
            ThingsApp(
                totalMl = 0,
                goalMl = 2000,
                messages = emptyList(),
                connectionState = "Scanning…",
                scanning = true,
                discoveredDevices = listOf(
                    BleManager.BleDevice("HidrateSpark PRO", "C4:2F:90:1A:3B:7E"),
                    BleManager.BleDevice("Smart Bottle 2", "D1:88:04:9C:22:10"),
                ),
                tempC = null,
                tds = null,
                batteryPct = null,
                charging = false,
                onScanClick = {},
                onDeviceClick = {},
                onDisconnectClick = {},
                scaleWeight = null,
                scaleRealtimeWeight = null,
                scaleR50 = null,
                scaleConnectionState = "Disconnected",
                scaleScanning = true,
                scaleDevices = emptyList(),
                scaleMetrics = null,
                scaleSex = Sex.Female,
                scaleAge = "28",
                scaleHeight = "165",
                scaleAthlete = false,
                onScaleScanClick = {},
                onScaleDeviceClick = {},
                onScaleDisconnectClick = {},
                onScaleSexChange = {},
                onScaleAgeChange = {},
                onScaleHeightChange = {},
                onScaleAthleteChange = {},
            )
        }
    }
}
