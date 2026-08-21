package com.vayunmathur.cast.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.cast.platform.CastViewModel

/** Everything the app has to offer, on one screen. */
@Composable
fun CastScreen(viewModel: CastViewModel) {
    val state by viewModel.uiState.collectAsState()
    // OpenDocument, not GetContent: it returns a URI the app can read for as long as it holds
    // it, which is what the local HTTP server needs, and it needs no storage permission.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.pickLocalFile(uri.toString()) }
    // Asked for plainly and never insisted on: without it the session still works, it just
    // loses the notification that is the only way to stop casting from outside the app.
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // The mDNS browse is tied to this screen being on screen: it is the only thing that wants
    // it, and leaving it running once the screen is gone is a radio kept awake for nothing.
    DisposableEffect(viewModel) {
        viewModel.startScan()
        onDispose { viewModel.stopScan() }
    }
    CastContent(
        state = state,
        actions = viewModel,
        onPickFile = { picker.launch(arrayOf("video/*", "audio/*", "image/*")) },
    )
}
