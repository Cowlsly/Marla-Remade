package com.vayunmathur.musicbrainz.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.musicbrainz.R

/** Formats a track length as `m:ss`, or blank when MusicBrainz has no duration on file. */
@Composable
fun durationLabel(durationMs: Int?): String {
    if (durationMs == null || durationMs <= 0) return ""
    val totalSeconds = durationMs / 1000
    return stringResource(
        R.string.duration_format,
        totalSeconds / 60,
        "%02d".format(totalSeconds % 60),
    )
}
