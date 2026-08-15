package com.vayunmathur.music.ui.components

import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music

@Composable
fun AddToPlaylistButton(backStack: NavBackStack<Route>, music: Music) {
    IconButton(onClick = { backStack.add(Route.AddToPlaylistDialog(music.id)) }) {
        IconMoreVert()
    }
}
