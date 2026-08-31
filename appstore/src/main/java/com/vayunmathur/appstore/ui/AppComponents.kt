package com.vayunmathur.appstore.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import java.util.Locale

/**
 * The app icon, from whichever of the three places actually has one.
 *
 * A locally installed app has a real launcher icon in PackageManager, which beats
 * re-downloading the listing's copy; a catalogue entry has a URL; a Play cluster row
 * sometimes has neither. Every screen needs the same fallback chain, so it lives here
 * rather than being written out again per call site.
 */
@Composable
fun AppIcon(
    app: UnifiedApp,
    installedIcon: Drawable?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    corner: Dp = 12.dp,
) {
    val shape = RoundedCornerShape(corner)
    val box = modifier.size(size).clip(shape)
    val bitmap = installedIcon?.let {
        runCatching {
            val px = (size.value * 2).toInt().coerceAtLeast(1)
            it.toBitmap(width = px, height = px).asImageBitmap()
        }.getOrNull()
    }
    when {
        bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = box,
            contentScale = ContentScale.Crop,
        )
        app.iconUrl != null -> AsyncImage(
            model = app.iconUrl,
            contentDescription = null,
            modifier = box,
            contentScale = ContentScale.Crop,
        )
        // No icon anywhere: an initial on a tinted tile reads better than a blank hole,
        // and keeps rows the same height whether or not the artwork loaded.
        else -> Box(
            box.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                app.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where an app came from. Purely factual — the sources are not ranked. */
@Composable
fun SourceChip(source: AppSource, modifier: Modifier = Modifier) {
    val label = stringResource(
        when (source) {
            AppSource.MODERN_APPS -> R.string.source_chip_modern_apps
            AppSource.FDROID -> R.string.source_chip_fdroid
            AppSource.GRAPHENEOS -> R.string.source_chip_grapheneos
            AppSource.PLAYSTORE -> R.string.source_chip_play
            AppSource.ACCRESCENT -> R.string.source_chip_accrescent
        }
    )
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** "4.6 ★" plus the rating count, when the source published one. */
@Composable
fun RatingLabel(app: UnifiedApp, modifier: Modifier = Modifier) {
    val rating = app.rating ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            String.format(Locale.US, "%.1f", rating),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(2.dp))
        IconStar(Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A full-width list row: icon, name, summary, and whatever the caller puts on the right.
 *
 * [stage] draws its own progress bar underneath, so a row that is mid-install says which
 * part of the install it is in rather than showing a bar that sits at 100% through
 * verification and commit.
 */
@Composable
fun AppRow(
    app: UnifiedApp,
    modifier: Modifier = Modifier,
    isInstalled: Boolean = false,
    stage: InstallStage? = null,
    installedIcon: Drawable? = null,
    versionLabel: String? = null,
    onClick: () -> Unit = {},
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, installedIcon, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = app.summary.ifBlank { app.author.orEmpty() }
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SourceChip(app.source)
                    RatingLabel(app)
                    if (isInstalled) {
                        IconCheck(Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (versionLabel != null) {
                    Text(
                        versionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        StageProgress(stage, Modifier.padding(top = 8.dp))
    }
}

/**
 * A carousel tile: icon over name over rating, in a fixed-width column.
 *
 * Fixed width rather than intrinsic so that the tiles in a row line up regardless of how
 * long each app's name is — a carousel of ragged columns reads as broken.
 */
@Composable
fun AppTile(
    app: UnifiedApp,
    modifier: Modifier = Modifier,
    isInstalled: Boolean = false,
    stage: InstallStage? = null,
    installedIcon: Drawable? = null,
    onClick: () -> Unit = {},
) {
    Column(
        modifier
            .width(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(app, installedIcon, size = 72.dp, corner = 18.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            app.name,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // Two lines' worth whatever the name's length, so tiles stay aligned.
            modifier = Modifier.heightIn(min = 32.dp),
        )
        // An in-flight install replaces the rating line rather than adding a third one, so a
        // tile mid-install stays roughly the height of its neighbours.
        when {
            stage != null -> StageProgress(stage)
            isInstalled -> Text(
                stringResource(R.string.installed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> RatingLabel(app)
        }
    }
}

/** Heading above a section of the home screen. */
@Composable
fun SectionHeader(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One horizontally scrolling row of [AppTile]s. */
@Composable
fun AppCarousel(
    apps: List<UnifiedApp>,
    installedPackages: Set<String>,
    installedIcons: Map<String, Drawable>,
    onAppClick: (UnifiedApp) -> Unit,
    modifier: Modifier = Modifier,
    stages: Map<String, InstallStage> = emptyMap(),
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(apps, key = { it.packageName }) { app ->
            AppTile(
                app = app,
                isInstalled = app.packageName in installedPackages,
                stage = stages[app.packageName],
                installedIcon = installedIcons[app.packageName],
                onClick = { onAppClick(app) },
            )
        }
    }
}

/**
 * The progress line for an in-flight install, or nothing when there isn't one.
 *
 * Download is determinate; verification and the PackageInstaller commit are not, and an
 * indeterminate bar is the honest way to say "still working, no idea how long".
 */
@Composable
fun StageProgress(stage: InstallStage?, modifier: Modifier = Modifier) {
    if (stage == null) return
    Column(modifier.fillMaxWidth()) {
        when (stage) {
            is InstallStage.Downloading -> LinearProgressIndicator(
                progress = { stage.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            is InstallStage.Failed -> Unit
            else -> LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text(
            stageLabel(stage),
            style = MaterialTheme.typography.labelSmall,
            color = if (stage is InstallStage.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
fun stageLabel(stage: InstallStage): String = when (stage) {
    InstallStage.Preparing -> stringResource(R.string.stage_preparing)
    is InstallStage.Downloading ->
        stringResource(R.string.stage_downloading, (stage.fraction * 100).toInt())
    InstallStage.Verifying -> stringResource(R.string.stage_verifying)
    InstallStage.Installing -> stringResource(R.string.stage_installing)
    is InstallStage.Failed -> stringResource(R.string.stage_failed, stage.reason)
}

/** A labelled value in the facts grid on the detail page. */
@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** A read-only chip, for categories and anti-features. */
@Composable
fun InfoChip(
    text: String,
    modifier: Modifier = Modifier,
    emphasise: Boolean = false,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (emphasise) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (emphasise) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** "12 MB", "980 kB" — SI units, because that is what stores quote. */
fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1_000L -> "$bytes B"
    bytes < 1_000_000L -> String.format(Locale.US, "%.0f kB", bytes / 1_000.0)
    bytes < 1_000_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
}

/** "1.2M+", "50K+" — the rounded form an install count is meaningful at. */
fun formatCount(count: Long): String = when {
    count <= 0L -> ""
    count < 1_000L -> count.toString()
    count < 1_000_000L -> "${count / 1_000}K+"
    count < 1_000_000_000L -> String.format(Locale.US, "%.1fM+", count / 1_000_000.0)
    else -> String.format(Locale.US, "%.1fB+", count / 1_000_000_000.0)
}
