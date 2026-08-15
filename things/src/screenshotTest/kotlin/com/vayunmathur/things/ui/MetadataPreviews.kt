package com.vayunmathur.things.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.things.platform.BleManager

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:things`. See `common-conventions-preview-metadata`.
 *
 * [ThingsApp] was already fully stateless — plain values in, callbacks out — so this app
 * needed no refactor at all, only these previews. That is the shape the other apps are
 * being moved towards.
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
                onScanClick = {},
                onDeviceClick = {},
                onDisconnectClick = {},
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
                onScanClick = {},
                onDeviceClick = {},
                onDisconnectClick = {},
            )
        }
    }
}
