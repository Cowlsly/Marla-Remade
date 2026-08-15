package com.vayunmathur.music.ui.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.music.platform.AlbumArt

/**
 * A track row that highlights the currently-playing song (title color/weight + container tint) and
 * shows album art. [leading] (rendered before the art) and [trailing] let each screen supply the
 * bits that differ (track numbers, remove/add-to-playlist actions, etc.).
 */
@Composable
fun TrackListItem(
    title: String,
    isPlaying: Boolean,
    artUri: Uri,
    onClick: () -> Unit,
    artSize: Dp = 48.dp,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        content = {
            Text(
                text = title,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
            )
        },
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = if (isPlaying) {
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                trailingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            ListItemDefaults.colors(containerColor = Color.Transparent)
        },
        trailingContent = trailing,
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leading?.invoke(this)
                AlbumArt(artUri, Modifier.size(artSize))
            }
        },
    )
}
