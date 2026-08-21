package com.vayunmathur.library.ui

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/**
 * The shape for one row of a grouped list, so a run of cards reads as a single block.
 *
 * The outer corners of the group are rounded and the inner joins are square, which is how
 * Material 3 Expressive draws grouped lists: the group is the object, and the rows are
 * divisions inside it rather than separate cards that happen to be adjacent.
 *
 * Radius comes from `MaterialTheme.shapes` rather than a literal, so a theme change moves the
 * whole app's list grouping at once. A single-row group ([count] == 1) is rounded on all four
 * corners, which is the same thing said consistently.
 */
@Composable
@ReadOnlyComposable
fun verticalShape(index: Int, count: Int): RoundedCornerShape {
    val outer = MaterialTheme.shapes.largeIncreased.topStart
    val inner = CornerSize(0.dp)
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
    )
}
