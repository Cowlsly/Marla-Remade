package com.vayunmathur.musicbrainz.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.musicbrainz.R
import com.vayunmathur.musicbrainz.Route
import com.vayunmathur.musicbrainz.ui.components.SecondaryText
import com.vayunmathur.musicbrainz.ui.components.TrackTrailing
import com.vayunmathur.musicbrainz.platform.download.DownloadItem
import com.vayunmathur.musicbrainz.platform.download.DownloadState
import com.vayunmathur.musicbrainz.platform.MusicBrainzActions
import com.vayunmathur.musicbrainz.platform.MusicBrainzViewModel

@Composable
fun DownloadsPage(backStack: NavBackStack<Route>, viewModel: MusicBrainzViewModel) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    DownloadsScreen(downloads.values.toList(), viewModel, backStack)
}

/** The download queue, so a long album fetch is visible from anywhere in the app. */
@Composable
fun DownloadsScreen(
    downloads: List<DownloadItem>,
    actions: MusicBrainzActions,
    backStack: NavBackStack<Route>,
) {
    AppScaffold(
        title = stringResource(R.string.downloads),
        backStack = backStack,
        actions = {
            if (downloads.any { it.state == DownloadState.Done || it.state == DownloadState.Failed }) {
                IconButton(actions::clearFinishedDownloads) { IconDelete() }
            }
        },
    ) { padding ->
        if (downloads.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.no_downloads),
                modifier = Modifier.fillMaxSize().padding(padding),
                message = stringResource(R.string.no_downloads_message),
                icon = { IconDownload() },
            )
            return@AppScaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(downloads, key = { it.id }) { item ->
                ListItem(
                    headlineContent = { Text(item.title) },
                    modifier = Modifier.fillMaxWidth(),
                    supportingContent = {
                        SecondaryText(
                            listOfNotNull(
                                item.artist.ifBlank { null },
                                item.album,
                                item.error ?: stringResource(item.state.labelRes()),
                            ).joinToString(" \u00B7 "),
                        )
                    },
                    trailingContent = {
                        TrackTrailing(
                            onDevice = item.state == DownloadState.Done,
                            download = item,
                            onDownload = {},
                            onCancel = { actions.cancelDownload(item.id) },
                        )
                    },
                )
            }
        }
    }
}

private fun DownloadState.labelRes(): Int = when (this) {
    DownloadState.Queued -> R.string.state_queued
    DownloadState.Searching -> R.string.state_searching
    DownloadState.Downloading -> R.string.state_downloading
    DownloadState.Tagging -> R.string.state_tagging
    DownloadState.Done -> R.string.state_done
    DownloadState.Failed -> R.string.state_failed
}




