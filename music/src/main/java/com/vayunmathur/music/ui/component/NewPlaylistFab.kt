package com.vayunmathur.music.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.music.ui.dialogs.CreatePlaylistDialog
import com.vayunmathur.music.util.MusicViewModel

@Composable
fun NewPlaylistFab(musicViewModel: MusicViewModel) {
    var showDialog by remember { mutableStateOf(false) }

    FloatingActionButton(onClick = { showDialog = true }) {
        IconAdd()
    }

    if (showDialog) {
        CreatePlaylistDialog(
            onDismiss = { showDialog = false },
            onCreate = { name ->
                musicViewModel.createPlaylist(name)
                showDialog = false
            }
        )
    }
}
