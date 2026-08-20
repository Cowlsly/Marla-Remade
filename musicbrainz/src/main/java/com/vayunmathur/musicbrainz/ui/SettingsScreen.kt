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
import com.vayunmathur.library.ui.SettingsSelectRow
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.musicbrainz.R
import com.vayunmathur.musicbrainz.Route
import com.vayunmathur.musicbrainz.platform.DownloadSource
import com.vayunmathur.musicbrainz.platform.MusicBrainzActions
import com.vayunmathur.musicbrainz.platform.MusicBrainzViewModel
import com.vayunmathur.musicbrainz.platform.SettingsUiState
import com.vayunmathur.musicbrainz.platform.TidalQuality

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
        // Resolved up front: SettingsSelectRow's label is a plain lambda, not composable.
        val sourceLabels = DownloadSource.entries.associateWith { stringResource(it.labelRes) }
        val qualityLabels = TidalQuality.entries.associateWith { stringResource(it.labelRes) }
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
                SettingsSelectRow(
                    title = stringResource(R.string.download_source),
                    selected = state.downloadSource,
                    options = DownloadSource.entries,
                    label = { sourceLabels.getValue(it) },
                    onSelect = actions::setDownloadSource,
                )
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
            SettingsSection(title = stringResource(R.string.download_source_tidal)) {
                if (state.tidalUsername != null) {
                    SettingsRow(
                        title = stringResource(R.string.tidal_account),
                        supportingText = state.tidalUsername,
                        trailingContent = {
                            TextButton(onClick = actions::signOutOfTidal) {
                                Text(stringResource(R.string.tidal_sign_out))
                            }
                        },
                    )
                } else {
                    SettingsRow(
                        title = stringResource(R.string.tidal_sign_in),
                        supportingText = stringResource(R.string.tidal_signed_out),
                        onClick = { backStack.add(Route.TidalLogin) },
                    )
                }
                // Quality only means anything once Tidal is the source audio comes from.
                if (state.downloadSource == DownloadSource.Tidal) {
                    SettingsSelectRow(
                        title = stringResource(R.string.tidal_quality),
                        selected = state.tidalQuality,
                        options = TidalQuality.entries,
                        label = { qualityLabels.getValue(it) },
                        onSelect = actions::setTidalQuality,
                    )
                }
            }
        }
    }
}

