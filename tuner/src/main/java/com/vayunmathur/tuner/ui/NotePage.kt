package com.vayunmathur.tuner.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.tuner.platform.TunerViewModel

/** Binds [NoteScreen] to the ViewModel. */
@Composable
fun NotePage(viewModel: TunerViewModel) {
    NoteScreen(
        state = viewModel.state,
        onToggleListening = {
            if (viewModel.state.listening) viewModel.stop() else viewModel.start()
        },
    )
}
