package com.vayunmathur.library.ui.game

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.IconSettings

/**
 * The app-bar actions a game screen carries.
 *
 * One order everywhere — achievements then settings — because the games had drifted into two orderings
 * and two different trophy glyphs, one of which was a raw `android.R.drawable` that CONTRIBUTING.md
 * forbids. Passing null for [onOpenSettings] covers the games whose settings live elsewhere.
 *
 * Meant for the `actions` slot of `AppScaffold`, which is why it extends [RowScope].
 */
@Composable
fun RowScope.GameTopBarActions(
    onOpenGameCenter: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    IconButton(onClick = onOpenGameCenter) { IconEmojiEvents() }
    if (onOpenSettings != null) {
        IconButton(onClick = onOpenSettings) { IconSettings() }
    }
}
