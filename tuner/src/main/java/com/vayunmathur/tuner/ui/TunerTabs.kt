package com.vayunmathur.tuner.ui

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconGrid
import com.vayunmathur.library.ui.IconMusicNote
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.tuner.R
import com.vayunmathur.tuner.platform.TunerViewModel

/**
 * The app's two tabs, gated behind microphone access.
 *
 * Capture is never started implicitly on first entry - it is opened by the button on the Note
 * tab. After that the activity's start/stop hooks release and restore it around the foreground,
 * so an interruption does not cost the user a tap. Disposal is a full stop, not a pause.
 */
@Composable
fun TunerTabs(viewModel: TunerViewModel) {
    PermissionsChecker(
        permissions = arrayOf(Manifest.permission.RECORD_AUDIO),
        text = stringResource(R.string.permission_title),
    ) {
        DisposableEffect(Unit) {
            onDispose { viewModel.stop() }
        }
        val tabs = listOf(
            PagerTab(stringResource(R.string.tab_note), { IconMusicNote() }) { NotePage(viewModel) },
            PagerTab(stringResource(R.string.tab_chord), { IconGrid() }) { ChordPage(viewModel) },
        )
        TabbedPagerScaffold(tabs = tabs, tabStyle = TabStyle.BottomNav)
    }
}
