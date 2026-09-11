package com.vayunmathur.health.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * Tells the user their medical records are app-local on this device.
 *
 * Shown when Health Connect reports no Personal Health Record support, which is every device below
 * Android 15 and any above it whose Health Connect module has not been updated. Without it the two
 * logs look identical to the Health-Connect-backed case and the user has no way to know their
 * records are not being shared with anything else.
 */
@Composable
fun MedicalStorageNotice(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.phr_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}
