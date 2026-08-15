package com.vayunmathur.music.ui.dialogs

import com.vayunmathur.library.ui.AddToListDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.music.R
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
import com.vayunmathur.music.platform.MusicViewModel

@Composable
fun AddToPlaylistDialog(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel, musicId: Long) {
    val playlists by musicViewModel.playlists.collectAsState()

    AddToListDialog(
        title = stringResource(R.string.dialog_add_to_playlist),
        options = playlists,
        itemLabel = { it.name },
        confirmLabel = stringResource(R.string.dialog_ok),
        dismissLabel = stringResource(R.string.dialog_cancel),
        itemKey = { it.id },
        createLabel = stringResource(R.string.new_playlist),
        canCreate = { name -> name.isNotBlank() && playlists.none { it.name == name.trim() } },
        onCreate = { name -> musicViewModel.createPlaylist(name.trim()) {} },
        onConfirm = { selected ->
            selected.forEach { musicViewModel.addMusicToPlaylist(it.id, musicId) {} }
            backStack.pop()
        },
        onDismiss = { backStack.pop() },
    )
}
