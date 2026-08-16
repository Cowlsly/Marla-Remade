package com.vayunmathur.camera.ui

import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.camera.util.AudioInputSource
import com.vayunmathur.camera.util.CodecSupport
import com.vayunmathur.camera.util.VideoCodec
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExposedDropdownMenuAnchorType
import com.vayunmathur.library.ui.ExposedDropdownMenuBox
import com.vayunmathur.library.ui.ExposedDropdownMenuDefaults
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : NavKey> SettingsPage(backStack: NavBackStack<T>, viewModel: CameraViewModel) {
    val locationEnabled by viewModel.locationEnabled.collectAsState()
    val videoCodec by viewModel.videoCodec.collectAsState()
    val audioInputSource by viewModel.audioInputSource.collectAsState()

    val requestLocation = rememberPermissionRequest(
        Manifest.permission.ACCESS_FINE_LOCATION
    ) { granted ->
        viewModel.setLocationEnabled(granted)
        if (granted) viewModel.updateLocation()
    }

    AppScaffold(
        title = stringResource(UiR.string.settings),
        backStack = backStack,
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
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = "${stringResource(videoCodec.labelRes)} — ${stringResource(videoCodec.descriptionRes)}",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                            label = { Text(stringResource(R.string.settings_video_codec_label)) }
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            availableCodecs.forEach { codec ->
                                DropdownMenuItem(
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
                                    onClick = {
                                        viewModel.setVideoCodec(codec)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_audio_source)) {
                var audioExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = audioExpanded,
                    onExpandedChange = { audioExpanded = it }
                ) {
                    OutlinedTextField(
                        value = "${stringResource(audioInputSource.labelRes)} — ${stringResource(audioInputSource.descriptionRes)}",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = audioExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        label = { Text(stringResource(R.string.settings_audio_source_label)) }
                    )
                    ExposedDropdownMenu(
                        expanded = audioExpanded,
                        onDismissRequest = { audioExpanded = false }
                    ) {
                        AudioInputSource.entries.forEach { source ->
                            DropdownMenuItem(
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
                                onClick = {
                                    viewModel.setAudioInputSource(source)
                                    audioExpanded = false
                                }
                            )
                        }
                    }
                }
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
