package com.vayunmathur.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.IconShuffle
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.music.R

/** The Play + Shuffle button pair shared by the album/artist/playlist detail screens. */
@Composable
fun PlayShuffleRow(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick = onPlay,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(50.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            IconPlay()
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.label_play))
        }

        Button(
            onClick = onShuffle,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(50.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            IconShuffle()
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.label_shuffle))
        }
    }
}
