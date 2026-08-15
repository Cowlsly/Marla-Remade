package com.vayunmathur.musicbrainz.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.musicbrainz.platform.download.DownloadItem
import com.vayunmathur.musicbrainz.platform.download.DownloadState

/**
 * The trailing control on a track row: a tick when it is already owned, a progress
 * spinner while it is being fetched, otherwise a download button.
 */
@Composable
fun TrackTrailing(
    onDevice: Boolean,
    download: DownloadItem?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    when {
        onDevice -> IconCheckCircle(tint = MaterialTheme.colorScheme.primary)
        download == null || download.state == DownloadState.Failed ->
            IconButton(onDownload) { IconDownload() }
        download.state == DownloadState.Done -> IconCheckCircle(tint = MaterialTheme.colorScheme.primary)
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (download.state == DownloadState.Downloading && download.progress > 0f) {
                CircularProgressIndicator({ download.progress }, modifier = Modifier.size(20.dp))
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
            IconButton(onCancel) { IconClose() }
        }
    }
}
