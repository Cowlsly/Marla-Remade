package com.vayunmathur.library.ui

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.window.core.layout.WindowSizeClass

/**
 * Width-aware horizontal content margin for list and detail panes.
 *
 * Phones keep the standard [Spacing.lg] margin; on expanded widths panes gain
 * the wider [Spacing.xl] gutter so content does not sit against the outer
 * screen edges. The threshold is the same
 * [WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND] breakpoint at which the Nav3
 * list-detail strategy in `MainNavigation` starts showing two panes side by
 * side, so single-pane and two-pane layouts never disagree about which margin
 * applies (a landscape phone wide enough for two panes gets the wide gutter in
 * both).
 *
 * There is no directive-level margin API to hook into: `PaneScaffoldDirective`
 * carries only partition counts, spacer sizes and preferred pane widths, and
 * `ListDetailPaneScaffoldDefaults` only pane order plus adapt strategies. The
 * shared scaffolds therefore apply this directly - see `DetailScaffold` and
 * `LazyListScaffold`. Inset handling is deliberately untouched here; the
 * manual chain in `MainNavigation` (disabled automatic insets, snackbar
 * re-padding, consume plus IME padding) stays exactly as it is.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun paneContentMargin(): Dp =
    if (
        currentWindowAdaptiveInfo().windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    ) {
        Spacing.xl
    } else {
        Spacing.lg
    }
