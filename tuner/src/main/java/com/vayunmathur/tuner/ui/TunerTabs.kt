package com.vayunmathur.tuner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconGrid
import com.vayunmathur.library.ui.IconMusicNote
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.tuner.R
import com.vayunmathur.tuner.platform.TunerViewModel

/**
 * The app's two tabs.
 *
 * Capture follows the tabs being on screen: there is no control for it, because a tuner that is
 * open and not listening has nothing to offer. Starting from here rather than from the activity
 * is what orders it after the permission gate, which the activity's start hook sits in front of.
 * After that the activity's start/stop hooks release and restore the microphone around the
 * foreground. Disposal is a full stop, not a pause.
 */
@Composable
fun TunerTabs(viewModel: TunerViewModel) {
    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }
    val tabs = listOf(
        PagerTab(stringResource(R.string.tab_note), { IconMusicNote() }) { NotePage(viewModel) },
        PagerTab(stringResource(R.string.tab_chord), { IconGrid() }) { ChordPage(viewModel) },
    )
    TabbedPagerScaffold(tabs = tabs, tabStyle = TabStyle.BottomNav)
}
