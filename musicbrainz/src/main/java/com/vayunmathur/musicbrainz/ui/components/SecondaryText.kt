package com.vayunmathur.musicbrainz.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun SecondaryText(text: String?, modifier: Modifier = Modifier) {
    if (!text.isNullOrBlank()) {
        Text(
            text,
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}
