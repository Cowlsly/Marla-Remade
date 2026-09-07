package com.vayunmathur.setupwizard.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.setupwizard.R
import com.vayunmathur.setupwizard.platform.ZoneInfo

/**
 * The time zone shortlist, each row showing the city over its offset and zone name so two
 * zones on the same offset can still be told apart.
 */
@Composable
fun TimeZonePickerDialog(
    zones: () -> List<ZoneInfo>,
    onSelected: (ZoneInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = remember { zones() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_time_zone)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(entries, key = { it.id }) { zone ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(zone) }
                            .padding(vertical = Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(zone.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            zone.standardName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
