package com.vayunmathur.camera.ui

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.camera.util.AudioInputSource
import com.vayunmathur.camera.util.CodecSupport
import com.vayunmathur.camera.util.VideoCodec
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey

@Composable
fun <T : NavKey> SettingsPage(backStack: NavBackStack<T>, viewModel: CameraViewModel) {
    val locationEnabled by viewModel.locationEnabled.collectAsState()
    val videoCodec by viewModel.videoCodec.collectAsState()
    val audioInputSource by viewModel.audioInputSource.collectAsState()
    val mirrorFront by viewModel.mirrorFront.collectAsState()

    val requestLocation = rememberPermissionRequest(
        Manifest.permission.ACCESS_FINE_LOCATION
    ) { granted ->
        viewModel.setLocationEnabled(granted)
        if (granted) viewModel.updateLocation()
    }

    AppScaffold(
        title = stringResource(UiR.string.settings),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            val availableCodecs = remember {
                buildList {
                    add(VideoCodec.AVC)
                    if (CodecSupport.isHevcEncoderAvailable) add(VideoCodec.HEVC)
                    if (CodecSupport.isHardwareAv1EncoderAvailable) add(VideoCodec.AV1)
                }
            }
            if (availableCodecs.size > 1) {
                SettingsSection(title = stringResource(R.string.settings_video_codec)) {
                    // Built from [OutlinedButton] + [DropdownMenu] rather than the library's
                    // `ExposedDropdownMenu` wrapper, which currently recurses into itself.
                    var expanded by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "${stringResource(videoCodec.labelRes)} — ${stringResource(videoCodec.descriptionRes)}",
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start,
                                maxLines = 1,
                            )
                            IconArrowDropDown()
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            availableCodecs.forEach { codec ->
                                SelectableDropdownMenuItem(
                                    selected = codec == videoCodec,
                                    onClick = {
                                        viewModel.setVideoCodec(codec)
                                        expanded = false
                                    },
                                    text = {
                                        Column {
                                            Text(stringResource(codec.labelRes))
                                            Text(
                                                stringResource(codec.descriptionRes),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    selectedLeadingIcon = { IconCheck() },
                                )
                            }
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_audio_source)) {
                // Built from [OutlinedButton] + [DropdownMenu] rather than the library's
                // `ExposedDropdownMenu` wrapper, which currently recurses into itself.
                var audioExpanded by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    OutlinedButton(
                        onClick = { audioExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "${stringResource(audioInputSource.labelRes)} — ${stringResource(audioInputSource.descriptionRes)}",
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start,
                            maxLines = 1,
                        )
                        IconArrowDropDown()
                    }
                    DropdownMenu(
                        expanded = audioExpanded,
                        onDismissRequest = { audioExpanded = false }
                    ) {
                        AudioInputSource.entries.forEach { source ->
                            SelectableDropdownMenuItem(
                                selected = source == audioInputSource,
                                onClick = {
                                    viewModel.setAudioInputSource(source)
                                    audioExpanded = false
                                },
                                text = {
                                    Column {
                                        Text(stringResource(source.labelRes))
                                        Text(
                                            stringResource(source.descriptionRes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                selectedLeadingIcon = { IconCheck() },
                            )
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_mirror_front)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_mirror_front_description),
                    checked = mirrorFront,
                    onCheckedChange = { viewModel.setMirrorFront(it) },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_location)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_location_description),
                    checked = locationEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            requestLocation()
                        } else {
                            viewModel.setLocationEnabled(false)
                        }
                    },
                )
            }
        }
    }
}
