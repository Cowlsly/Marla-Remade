package com.vayunmathur.cast.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.platform.CastActions
import com.vayunmathur.cast.platform.CastConnection
import com.vayunmathur.cast.platform.CastUiState
import com.vayunmathur.cast.platform.MirrorPhase
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi - comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

private val LivingRoomTv = CastDevice(
    id = "3f1a9c",
    friendlyName = "Living Room TV",
    host = "192.168.1.42",
    port = 41_337,
    protocolVersion = 1,
)

private val Receivers = listOf(
    LivingRoomTv,
    CastDevice(
        id = "8b2d41",
        friendlyName = "Bedroom TV",
        host = "192.168.1.51",
        port = 38_211,
        protocolVersion = 1,
    ),
)

/**
 * Store-listing screenshots.
 *
 * Driven through [CastContent], which is stateless by design, so no socket, no mDNS browse and no
 * screen-capture projection has to exist for these to render - none of which Layoutlib could provide.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but is not
 * collected as a screenshot test. They must also be class members rather than top-level functions, and
 * the listing order comes from the function names (Preview1., Preview2.).
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-devices", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Devices() {
        DynamicTheme(darkTheme = true) {
            CastContent(
                state = CastUiState(devices = Receivers, isScanning = true),
                actions = CastActions.Noop,
            )
        }
    }

    /** The one screen that is new in MA Cast: six digits read off the TV, typed once per device. */
    @PreviewTest
    @Preview(name = "2-pair", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Pair() {
        DynamicTheme(darkTheme = true) {
            CastContent(
                state = CastUiState(
                    devices = Receivers,
                    connectedDevice = LivingRoomTv,
                    connection = CastConnection.AwaitingCode,
                    pairAttemptsLeft = 3,
                ),
                actions = CastActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-ready", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Ready() {
        DynamicTheme(darkTheme = true) {
            CastContent(
                state = CastUiState(
                    devices = Receivers,
                    connectedDevice = LivingRoomTv,
                    connection = CastConnection.Connected,
                ),
                actions = CastActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-mirroring", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4Mirroring() {
        DynamicTheme(darkTheme = true) {
            CastContent(
                state = CastUiState(
                    devices = Receivers,
                    connectedDevice = LivingRoomTv,
                    connection = CastConnection.Connected,
                    mirrorPhase = MirrorPhase.Mirroring,
                ),
                actions = CastActions.Noop,
            )
        }
    }
}
