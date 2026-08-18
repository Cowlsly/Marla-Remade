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
import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.ReceiveUiState
import com.vayunmathur.share.platform.ReceivedFile
import com.vayunmathur.share.platform.ShareActions
import com.vayunmathur.share.platform.TransferProgress

/** Phone-shaped, roughly 1080x2340 at xxhdpi - comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

private val AnnouncedFiles = listOf(
    PendingFile(name = "holiday.jpg", sizeBytes = 2_411_724, mimeType = "image/jpeg"),
    PendingFile(name = "itinerary.pdf", sizeBytes = 184_320, mimeType = "application/pdf"),
)

private val StagedFiles = listOf(
    ReceivedFile(
        name = "holiday.jpg",
        sizeBytes = 2_411_724,
        mimeType = "image/jpeg",
        uri = "content://com.vayunmathur.share.fileprovider/my_docs/received/holiday.jpg".toUri(),
    ),
    ReceivedFile(
        name = "itinerary.pdf",
        sizeBytes = 184_320,
        mimeType = "application/pdf",
        uri = "content://com.vayunmathur.share.fileprovider/my_docs/received/itinerary.pdf".toUri(),
    ),
)

private fun progress(
    state: ShareState,
    pendingFiles: List<PendingFile> = emptyList(),
    receivedFiles: List<ReceivedFile> = emptyList(),
    bytesReceived: Long = 0,
    error: String? = null,
) = TransferProgress(
    state = state,
    pendingFiles = pendingFiles,
    receivedFiles = receivedFiles,
    bytesSent = 0,
    bytesReceived = bytesReceived,
    error = error,
)

/**
 * Store-listing screenshots for `:share`'s receive flow.
 *
 * These drive the stateless halves of the screen. `ShareReceiveScreen` itself needs a
 * `ShareViewModel`, and an incoming transfer is normally a `Connection` holding a TCP
 * socket and a native session handle - Layoutlib can load neither - so
 * [IncomingRequestContent] takes a plain [TransferProgress] and is fed literal data here.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1., Preview2.).
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-visible", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Visible() {
        DynamicTheme(darkTheme = true) {
            Surface {
                ShareReceiveContent(
                    uiState = ReceiveUiState(
                        isVisible = true,
                        localName = "Pixel 8",
                        listenPort = 43117,
                    ),
                    actions = ShareActions.Noop,
                )
            }
        }
    }

    @PreviewTest
    @Preview(name = "2-incoming", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Incoming() {
        Cards {
            IncomingRequestContent(
                remoteEndpoint = "192.168.1.24:39184",
                progress = progress(ShareState.AwaitingAccept, pendingFiles = AnnouncedFiles),
                actions = ShareActions.Noop,
                onAccept = {},
                onReject = {},
                onDisconnect = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-receiving", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Receiving() {
        Cards {
            IncomingRequestContent(
                remoteEndpoint = "192.168.1.24:39184",
                progress = progress(
                    ShareState.Transferring,
                    pendingFiles = AnnouncedFiles,
                    bytesReceived = 1_260_000,
                ),
                actions = ShareActions.Noop,
                onAccept = {},
                onReject = {},
                onDisconnect = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-received", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4Received() {
        Cards {
            IncomingRequestContent(
                remoteEndpoint = "192.168.1.24:39184",
                progress = progress(
                    ShareState.Completed,
                    pendingFiles = AnnouncedFiles,
                    receivedFiles = StagedFiles,
                ),
                actions = ShareActions.Noop,
                onAccept = {},
                onReject = {},
                onDisconnect = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-failed", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview5Failed() {
        Cards {
            IncomingRequestContent(
                remoteEndpoint = "192.168.1.24:39184",
                // The reason comes from the native session, so the UI can name the phase
                // that broke instead of showing a return code.
                progress = progress(
                    ShareState.Failed,
                    error = "peer rejected the connection (status 8004)",
                ),
                actions = ShareActions.Noop,
                onAccept = {},
                onReject = {},
                onDisconnect = {},
            )
        }
    }

    @Composable
    private fun Cards(content: @Composable () -> Unit) {
        DynamicTheme(darkTheme = true) {
            Surface {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }

    @Composable
    private fun Surface(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            content()
        }
    }
}
