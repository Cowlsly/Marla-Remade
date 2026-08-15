package com.vayunmathur.euicc.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.vayunmathur.euicc.ui.QrScannerScreen
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

@Composable
fun DownloadDialog(onDownload: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    if (scanning) {
        QrScannerScreen(
            onResult = { code = it; scanning = false },
            onCancel = { scanning = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Activation code") },
                )
                TextButton(onClick = { scanning = true }) { Text("Scan QR code") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (code.isNotBlank()) onDownload(code.trim()) },
                enabled = code.isNotBlank(),
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
