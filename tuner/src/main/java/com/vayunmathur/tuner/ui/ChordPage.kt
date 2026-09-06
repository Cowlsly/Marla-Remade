package com.vayunmathur.tuner.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.tuner.platform.TunerViewModel

/** Binds [ChordScreen] to the ViewModel. */
@Composable
fun ChordPage(viewModel: TunerViewModel) {
    ChordScreen(
        state = viewModel.state,
        onSelectInstrument = viewModel::selectInstrument,
    )
}
