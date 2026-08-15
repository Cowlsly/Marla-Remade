package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * An action shown as a trailing icon button in [TopAppBarOverlay].
 *
 * @param icon the icon content for the button (e.g. `{ IconShare() }`).
 * @param contentDescription accessibility description for the button.
 * @param onClick invoked when the button is tapped.
 */
data class OverlayAction(
    val icon: @Composable () -> Unit,
    val contentDescription: String,
    val onClick: () -> Unit,
)

/**
 * A title-less top bar that overlays full-bleed content rather than occupying
 * scaffold height.
 *
 * Several apps that show a map, photo, or canvas full-bleed need only a back
 * affordance and a few actions floating over the content. Each had hand-rolled
 * its own `Row` with `statusBarsPadding` and ad-hoc circular or translucent
 * backgrounds, drifting apart on insets, spacing, and legibility over
 * bright/dark content.
 *
 * This is that [Row], normalised: it does not reserve scaffold height and is
 * meant to be overlaid by the caller (e.g.
 * `Box { content(); TopAppBarOverlay(Modifier.align(Alignment.TopCenter)) }`).
 * Every button — the leading back button and each trailing action — is drawn
 * with an opaque circular background via [FilledTonalIconButton] so icons stay
 * legible over any content. The row insets for the status bar
 * ([Modifier.statusBarsPadding]), fills the width, and applies horizontal
 * [Spacing.lg] and top [Spacing.sm].
 *
 * Intended for apps that use ONLY this overlay bar (no full [TopAppBar]s
 * elsewhere on the same screen).
 *
 * @param modifier external modifier chained before the internal overlay
 *   padding/insets (the caller typically aligns this bar in a [androidx.compose.foundation.layout.Box]).
 * @param onNavigateBack if non-null, a leading back button is shown at the
 *   start.
 * @param actions trailing actions shown at the end as opaque circular icon
 *   buttons.
 */
@Composable
fun TopAppBarOverlay(
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    actions: List<OverlayAction> = emptyList(),
) {
    Row(
        modifier = modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onNavigateBack != null) {
            FilledTonalIconButton(onClick = onNavigateBack) {
                IconBack()
            }
        } else {
            // Keep trailing actions right-aligned when there is no back button.
            Spacer(modifier = Modifier.weight(1f))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (action in actions) {
                FilledTonalIconButton(onClick = action.onClick) {
                    action.icon()
                }
            }
        }
    }
}
