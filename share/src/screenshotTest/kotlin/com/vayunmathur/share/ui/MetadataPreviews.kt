package com.vayunmathur.share.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.SendUiState
import com.vayunmathur.share.platform.ShareActions
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice

/** Phone-shaped, roughly 1080x2340 at xxhdpi - comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

private val Peers = listOf(
    NearbyDevice(
        endpointId = "K7QP",
        endpointName = "Vayun's Pixel 7 Pro",
        host = "192.168.1.24",
        port = 39184,
        source = DiscoverySource.Both,
    ),
    NearbyDevice(
        endpointId = "3ZLM",
        endpointName = "Galaxy S24",
        host = "192.168.1.31",
        port = 41022,
        source = DiscoverySource.Nsd,
    ),
    NearbyDevice(
        endpointId = "A1C9",
        endpointName = "ThinkPad X1",
        source = DiscoverySource.Ble,
        extra = "0c00fc9f5ea1c9",
    ),
)

private val PickedFiles = listOf(
    "content://media/external/images/media/1041".toUri(),
    "content://media/external/images/media/1042".toUri(),
)

private val PickedNames = listOf("holiday.jpg", "itinerary.pdf")

/**
 * Store-listing screenshots for `:share`'s send flow.
 *
 * The app is send-only in-app: receiving is notification-driven, and a notification cannot be
 * rendered by Layoutlib. So these drive the stateless halves of the send screen instead.
 * [ShareSendContent] takes a plain [SendUiState] with `activeConnection = null`, and the
 * in-flight states go through [TransferCard], because a live `Connection` owns a TCP socket and
 * a native session handle that Layoutlib cannot load.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1., Preview2.).
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-pick", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Pick() {
        Screen {
            ShareSendContent(
                uiState = SendUiState(
                    outgoingUris = PickedFiles,
                    outgoingDisplayNames = PickedNames,
                ),
                actions = ShareActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-devices", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Devices() {
        Screen {
            ShareSendContent(
                uiState = SendUiState(
                    discoveredDevices = Peers,
                    isScanning = true,
                    outgoingUris = PickedFiles,
                    outgoingDisplayNames = PickedNames,
                ),
                actions = ShareActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-waiting", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Waiting() {
        Cards {
            TransferCard(
                endpoint = "Vayun's Pixel 7 Pro",
                state = ShareState.AwaitingAccept,
                bytesSent = 0,
                error = null,
                onDisconnect = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-sending", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4Sending() {
        Cards {
            TransferCard(
                endpoint = "Vayun's Pixel 7 Pro",
                state = ShareState.Transferring,
                bytesSent = 1_260_000,
                error = null,
                onDisconnect = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-sent", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview5Sent() {
        Cards {
            TransferCard(
                endpoint = "Vayun's Pixel 7 Pro",
                state = ShareState.Completed,
                bytesSent = 2_596_044,
                error = null,
                onDisconnect = {},
            )
            // The reason comes from the native session, so the UI can name the phase that
            // broke instead of showing a return code.
            TransferCard(
                endpoint = "Galaxy S24",
                state = ShareState.Failed,
                bytesSent = 0,
                error = "peer rejected the connection (status 8004)",
                onDisconnect = {},
            )
        }
    }

    @Composable
    private fun Cards(content: @Composable () -> Unit) {
        Screen {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }

    @Composable
    private fun Screen(content: @Composable () -> Unit) {
        DynamicTheme(darkTheme = true) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                content()
            }
        }
    }
}
