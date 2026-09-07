package com.vayunmathur.euicc.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.vayunmathur.euicc.R
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * Shows the eUICC's EID as both a QR code and readable digits, matching what the platform
 * LPA offers: carriers either scan it or ask the user to read it out.
 *
 * The digits are grouped in fours because a 32-character run is impossible to dictate or
 * check by eye.
 */
@Composable
fun EidDialog(eid: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.eid_qr_code_dialog_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                EidQrCode(eid)
                Text(
                    eid.chunked(4).joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    ExternalIntents.copyToClipboard(context, eid, "EID")
                    onDismiss()
                },
            ) { Text(stringResource(R.string.copy_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

/**
 * Draws [eid] as a QR code.
 *
 * The matrix is rendered straight to a [Canvas] rather than through an intermediate
 * `Bitmap`, so it stays crisp at any size and needs no bitmap recycling. Encoding is
 * [remember]ed on the EID, which never changes for a device - it is a per-eUICC constant.
 */
@Composable
private fun EidQrCode(eid: String) {
    // ERROR_CORRECTION_M is what a printed-label-sized code wants: enough redundancy to
    // survive a mediocre camera without inflating the module count.
    val matrix: BitMatrix? = remember(eid) {
        runCatching {
            QRCodeWriter().encode(
                eid,
                BarcodeFormat.QR_CODE,
                QR_SIZE,
                QR_SIZE,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to QR_QUIET_ZONE,
                ),
            )
        }.getOrNull()
    }
    if (matrix == null) return

    val foreground = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier = Modifier
            .fillMaxWidth(QR_WIDTH_FRACTION)
            .aspectRatio(1f),
    ) {
        val module = size.width / matrix.width
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (!matrix.get(x, y)) continue
                drawRect(
                    color = foreground,
                    topLeft = Offset(x * module, y * module),
                    // A hair over one module so neighbours meet without a seam from
                    // fractional rounding.
                    size = Size(module + 0.5f, module + 0.5f),
                )
            }
        }
    }
}

/**
 * Requested matrix size. ZXing rounds up to whatever the content needs, so this is a floor
 * rather than an exact module count; the Canvas scales whatever comes back.
 */
private const val QR_SIZE = 256

/** Quiet zone in modules. Four is the QR spec's minimum for reliable detection. */
private const val QR_QUIET_ZONE = 4

/** How much of the dialog's width the code occupies. */
private const val QR_WIDTH_FRACTION = 0.72f
