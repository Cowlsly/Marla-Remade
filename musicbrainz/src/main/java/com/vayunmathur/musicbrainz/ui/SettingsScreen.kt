package com.vayunmathur.musicbrainz.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconFolder
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.musicbrainz.R
import com.vayunmathur.musicbrainz.Route
import com.vayunmathur.musicbrainz.platform.MusicBrainzActions
import com.vayunmathur.musicbrainz.platform.MusicBrainzViewModel
import com.vayunmathur.musicbrainz.platform.SettingsUiState

/**
 * Binds the settings screen to the ViewModel.
 *
 * The folder picker lives here because it needs an activity result launcher, which is
 * exactly what a `@Preview` cannot provide.
 */
@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: MusicBrainzViewModel) {
    val state by viewModel.settings.collectAsStateWithLifecycle()
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { viewModel.setMusicFolder(it.toString()) } }

    SettingsScreen(
        state = state,
        actions = viewModel,
        backStack = backStack,
        onPickFolder = { folderLauncher.launch(null) },
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: MusicBrainzActions,
    backStack: NavBackStack<Route>,
    onPickFolder: () -> Unit,
) {
    AppScaffold(title = stringResource(R.string.settings), backStack = backStack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(title = stringResource(R.string.music_folder)) {
                SettingsRow(
                    title = stringResource(R.string.choose_folder),
                    supportingText = state.folderName
                        ?: stringResource(R.string.no_folder_selected),
                    onClick = onPickFolder,
                    leadingContent = { IconFolder() },
                )
                SettingsRow(
                    title = stringResource(R.string.rescan_library),
                    supportingText = if (state.scanning) {
                        stringResource(R.string.scanning)
                    } else {
                        pluralStringResource(
                            R.plurals.indexed_tracks,
                            state.indexedTracks,
                            state.indexedTracks,
                        )
                    },
                    enabled = state.folderName != null && !state.scanning,
                    onClick = actions::rescanLibrary,
                    leadingContent = { IconRefresh() },
                    trailingContent = if (state.scanning) {
                        { CircularProgressIndicator() }
                    } else {
                        null
                    },
                )
            }
            SettingsSection(title = stringResource(R.string.downloads)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.embed_cover_art),
                    checked = state.embedCoverArt,
                    onCheckedChange = actions::setEmbedCoverArt,
                    supportingText = stringResource(R.string.embed_cover_art_description),
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.fetch_lyrics),
                    checked = state.fetchLyrics,
                    onCheckedChange = actions::setFetchLyrics,
                    supportingText = stringResource(R.string.fetch_lyrics_description),
                )
            }
        }
    }
}

