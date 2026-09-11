package com.vayunmathur.health.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedCard
import com.vayunmathur.library.ui.Text

/**
 * A form row that opens a picker screen instead of the keyboard.
 *
 * Deliberately a row rather than a read-only [com.vayunmathur.library.ui.LabeledTextField]. A text
 * field that cannot be typed into has to be disabled to stop it taking focus and raising the IME,
 * and a disabled field greys out its contents — so a vaccine the user has already chosen would
 * render as though it were unavailable. The chevron also says "this opens something", which a text
 * field does not.
 */
@Composable
fun PickerField(
    label: String,
    value: String,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    ) {
        ListItem(
            overlineContent = { Text(label) },
            headlineContent = {
                if (value.isNotBlank()) {
                    Text(value)
                } else {
                    Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            trailingContent = { IconChevronRight() },
        )
    }
}
