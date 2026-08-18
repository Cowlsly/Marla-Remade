package com.vayunmathur.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.ui.components.PopupPlacement
import com.vayunmathur.launcher.ui.components.launcherPopupSurface
import com.vayunmathur.library.ui.IconApps
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconWallpaper
import com.vayunmathur.library.ui.IconWidgets
import com.vayunmathur.library.ui.SettingsRow

/**
 * What a long press on bare wallpaper offers, as Launcher3's workspace options popup does.
 *
 * A popup anchored at the finger rather than a jump to a full-screen settings page. The difference
 * is not decoration: the things anyone long-presses the wallpaper for are all one tap away here, and
 * none of them is worth leaving the home screen for.
 *
 * The four rows, their order and their labels are `WorkspaceLongPressOptions.getAll` on a default
 * build. Launcher3 adds two more only behind feature flags — "Edit Home Screen" under
 * `MULTI_SELECT_EDIT_MODE` and the folder/organizer pair under `condoPlanner()` — and neither has an
 * equivalent here, so neither is offered.
 */
@Composable
fun WorkspaceOptionsContent(
    modifier: Modifier = Modifier,
    placement: PopupPlacement = PopupPlacement(),
    progress: () -> Float = { 1f },
    onPickWallpaper: () -> Unit = {},
    onOpenWidgets: () -> Unit = {},
    onOpenAppsList: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .width(POPUP_WIDTH)
            .launcherPopupSurface(placement, progress),
    ) {
        SettingsRow(
            title = "Wallpaper & style",
            leadingContent = { IconWallpaper() },
            onClick = onPickWallpaper,
        )
        SettingsRow(
            title = "Widgets",
            leadingContent = { IconWidgets() },
            onClick = onOpenWidgets,
        )
        // Launcher3 carries this for the benefit of anyone who cannot perform the swipe; it opens
        // exactly what the swipe would.
        SettingsRow(
            title = "Apps list",
            leadingContent = { IconApps() },
            onClick = onOpenAppsList,
        )
        SettingsRow(
            title = "Home settings",
            leadingContent = { IconSettings() },
            onClick = onOpenSettings,
        )
    }
}

/** The same width as the item popup, so the two read as one control in two states. */
private val POPUP_WIDTH = 216.dp
