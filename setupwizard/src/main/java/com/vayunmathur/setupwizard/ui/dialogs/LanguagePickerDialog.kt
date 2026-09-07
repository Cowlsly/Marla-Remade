package com.vayunmathur.setupwizard.ui.dialogs

import androidx.compose.foundation.clickable
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
import java.util.Locale

/**
 * The system language picker.
 *
 * Every entry is written in its own language, which is the only way the list is usable to
 * someone who cannot read the one the device is currently in.
 *
 * [languages] is a function rather than a list because enumerating the framework's asset
 * locales is not free and this dialog is opened rarely; it is called once when the dialog
 * appears.
 */
@Composable
fun LanguagePickerDialog(
    languages: () -> List<Locale>,
    onSelected: (Locale) -> Unit,
    onDismiss: () -> Unit,
) {
    val locales = remember { languages() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_your_language)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(locales, key = { it.toLanguageTag() }) { locale ->
                    Text(
                        locale.getDisplayName(locale),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(locale) }
                            .padding(vertical = Spacing.md),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
