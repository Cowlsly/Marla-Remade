package com.vayunmathur.cast.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.CastPlayerState
import com.vayunmathur.cast.platform.CastActions
import com.vayunmathur.cast.platform.CastConnection
import com.vayunmathur.cast.platform.CastSource
import com.vayunmathur.cast.platform.CastUiState
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi - comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

private val LivingRoomTv = CastDevice(
    id = "3f1a9c",
    friendlyName = "Living Room TV",
    host = "192.168.1.42",
    model = "Chromecast Ultra",
    statusText = "Ready To Cast",
    capabilities = 1,
)

private val Receivers = listOf(
    LivingRoomTv,
    CastDevice(
        id = "8b2d41",
        friendlyName = "Kitchen speaker",
        host = "192.168.1.51",
        model = "Google Nest Mini",
        capabilities = 4,
    ),
    CastDevice(
        id = "c07e15",
        friendlyName = "Whole home",
        host = "192.168.1.60",
        model = "Speaker group",
        capabilities = 1 shl 5,
    ),
)

/**
 * Store-listing screenshots.
 *
 * Driven through [CastContent], which is stateless by design, so no socket, no mDNS browse and
 * no local HTTP server has to exist for these to render - none of which Layoutlib could provide.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but is
 * not collected as a screenshot test. They must also be class members rather than top-level
 * functions, and the listing order comes from the function names (Preview1., Preview2.).
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

    @PreviewTest
    @Preview(name = "2-picked", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Picked() {
        DynamicTheme(darkTheme = true) {
            CastContent(
                state = CastUiState(
                    devices = Receivers,
                    pendingSource = CastSource.LocalFile(
                        uri = "content://media/external/video/media/2041",
                        label = "holiday-2024.mp4",
                        mimeType = "video/mp4",
                    ),
                ),
                actions = CastActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-playing", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Playing() {
        DynamicTheme(darkTheme = true) {
            CastContent(
                state = CastUiState(
                    devices = Receivers,
                    connectedDevice = LivingRoomTv,
                    connection = CastConnection.Connected,
                    playerState = CastPlayerState.Playing,
                    title = "holiday-2024.mp4",
                    positionSec = 184.0,
                    durationSec = 742.0,
                    volumeLevel = 0.55,
                ),
                actions = CastActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-paused", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4Paused() {
        DynamicTheme(darkTheme = true) {
            CastContent(
                state = CastUiState(
                    devices = Receivers,
                    connectedDevice = LivingRoomTv,
                    connection = CastConnection.Connected,
                    playerState = CastPlayerState.Paused,
                    title = "Sur les toits de Paris",
                    positionSec = 96.0,
                    durationSec = 305.0,
                    volumeLevel = 0.3,
                    muted = true,
                ),
                actions = CastActions.Noop,
            )
        }
    }
}
