package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
 * A top bar that overlays full-bleed content rather than occupying scaffold height.
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
 * legible over any content.
 *
 * **The bar itself never draws a container.** That is the whole point of it: the
 * content behind shows straight through, and only the buttons are opaque. A
 * [title] is subject to the same rule — whatever goes in there has to carry its
 * own background if it needs one, exactly as the buttons do.
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
 * @param title optional content between the back button and the actions, taking
 *   whatever width they leave. It is given no horizontal inset of its own — the
 *   screen margin is applied to the button groups instead — so a scrolling title
 *   can run to the edge of the window and manage its own content padding rather
 *   than being clipped against a margin. Absent by default, which is the shape
 *   every caller had before there was one.
 */
@Composable
fun TopAppBarOverlay(
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    actions: List<OverlayAction> = emptyList(),
    title: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        // The screen margin lives on the two button groups rather than on this row, so a title
        // can span the full width. With no title the result is identical: a full-width row padded
        // by Spacing.lg puts its first and last child in exactly the same place.
        modifier = modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onNavigateBack != null) {
            Row(modifier = Modifier.padding(start = Spacing.lg)) {
                FilledTonalIconButton(onClick = onNavigateBack) {
                    IconBack()
                }
            }
        }

        if (title != null) {
            // Weighted, so the actions keep their intrinsic width and stay pinned to the end no
            // matter how wide the title wants to be. A title that overflows has to scroll or
            // ellipsize inside this slot; it can never push a button off the screen.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                content = title,
            )
        } else if (onNavigateBack == null) {
            // Keep trailing actions right-aligned when there is neither a back button nor a title.
            Spacer(modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.padding(end = Spacing.lg),
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
