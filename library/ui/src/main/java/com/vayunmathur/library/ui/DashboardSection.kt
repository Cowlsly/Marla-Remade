package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The titled card sections a dashboard/home screen is built from.
 *
 * Weather, the app store, the games hub, travel and health had each grown their
 * own `SectionHeader` + rounded `Surface` card combo, drifting on corner radius,
 * container colour and the "See all" affordance. This promotes health's
 * already-factored version to the one shared here.
 */

/**
 * A section heading: a [titleSmall] title, an optional leading icon, and an
 * optional trailing text button (the "See all" affordance).
 *
 * [leadingIcon] receives the [Modifier] and tint to draw with so the caller can
 * pass any of the shared `IconXyz` composables.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    leadingIcon: (@Composable (Modifier, Color) -> Unit)? = null,
    accentColor: Color? = null,
) {
    val textColor = accentColor?.copy(alpha = 0.85f) ?: MaterialTheme.colorScheme.onSurfaceVariant
    val iconColor = accentColor ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            leadingIcon(Modifier.size(16.dp), iconColor)
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * A dashboard card: an optional [SectionHeader] over a rounded `Surface`
 * (`surfaceContainerLow`) whose children stack in a [Column].
 *
 * The card is inset from the screen edge by [Spacing.lg]; rows inside supply
 * their own padding (or use [DashboardSectionDivider] between them).
 */
@Composable
fun DashboardSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    leadingIcon: (@Composable (Modifier, Color) -> Unit)? = null,
    accentColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            SectionHeader(
                title = title,
                actionLabel = actionLabel,
                onAction = onAction,
                leadingIcon = leadingIcon,
                accentColor = accentColor,
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** Thin inset divider between rows inside a [DashboardSection]. */
@Composable
fun DashboardSectionDivider(insetStart: Dp = 56.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = insetStart),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}
