package com.vayunmathur.appstore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.security.TrustProfile
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.util.AppDetailActions
import com.vayunmathur.appstore.util.AppDetailUiState
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.sharedContainer

/** Binds [AppStoreViewModel] to the stateless [AppDetailScreen]. */
@Composable
fun AppDetailPage(
    viewModel: AppStoreViewModel,
    onBack: () -> Unit,
    onOpenTrust: () -> Unit = {},
) {
    val state by viewModel.detail.collectAsState()
    AppDetailScreen(state = state, actions = viewModel, onBack = onBack, onOpenTrust = onOpenTrust)
}

/**
 * One app's page: what it is, what it looks like, and what installing it would mean.
 *
 * The previous version led with a wall of label/value rows, which is a debug dump rather
 * than a listing. The order here is the order the questions actually get asked —
 * screenshots, the install button, the description, then the facts, then provenance.
 *
 * Stateless so it can be rendered from a `@Preview` — see `src/screenshotTest`, which is
 * where the store listing images come from.
 */
@Composable
fun AppDetailScreen(
    state: AppDetailUiState,
    actions: AppDetailActions,
    onBack: () -> Unit = {},
    onOpenTrust: () -> Unit = {},
) {
    val app = state.app ?: return
    var showUninstallConfirm by remember { mutableStateOf(false) }

    AppScaffold(
        title = { Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onNavigateBack = onBack,
        alignment = AppBarAlignment.Center,
        actions = {
            IconButton(onClick = { actions.shareApp(app) }) { IconShare() }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(state)
            InstallActions(state, actions, onUninstall = { showUninstallConfirm = true })
            if (app.screenshots.isNotEmpty()) ScreenshotStrip(app.screenshots)
            Facts(app)
            Description(app)
            WhatsNew(app)
            Chips(app)
            TrustCard(app.source, state.verification, onOpenTrust)
            Links(app, actions)
            Details(app)
        }
    }

    if (showUninstallConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.uninstall_3, app.name),
            message = stringResource(R.string.this_will_uninstall_you_can_reinstall_la, app.packageName),
            confirmLabel = stringResource(R.string.uninstall),
            dismissLabel = stringResource(UiR.string.cancel),
            onConfirm = { actions.uninstallApp(app.packageName) },
            onDismiss = { showUninstallConfirm = false },
            destructive = true,
        )
    }
}

@Composable
private fun Header(state: AppDetailUiState) {
    val app = state.app ?: return
    Row(
        Modifier
            .sharedContainer("appstore-app-${app.packageName}")
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app, state.installedIcon, size = 80.dp, corner = 20.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            app.author?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceChip(app.source)
                if (state.isLoadingDetails) CircularProgressIndicator(Modifier.size(12.dp))
            }
        }
    }
}

/**
 * Rating, installs, size and age rating, in whatever subset the source published.
 *
 * F-Droid publishes none of the first three, so the row collapses to just the size for an
 * F-Droid app rather than showing four empty cells.
 */
@Composable
private fun Facts(app: UnifiedApp) {
    val cells = buildList<Pair<String, String>> {
        app.rating?.let {
            add(stringResource(R.string.fact_rating) to String.format(java.util.Locale.US, "%.1f★", it))
        }
        if (app.installs > 0) add(stringResource(R.string.fact_installs) to formatCount(app.installs))
        if (app.sizeBytes > 0) add(stringResource(R.string.fact_size) to formatSize(app.sizeBytes))
        app.contentRating?.let { add(stringResource(R.string.fact_rated) to it) }
        app.versionName?.let { add(stringResource(R.string.fact_version) to it) }
    }
    if (cells.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        cells.take(4).forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(32.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            StatCell(label, value, Modifier.weight(1f))
        }
    }
}

/**
 * The primary action.
 *
 * There is exactly one full-width button and it always says what tapping it will do —
 * including mid-install, where it reports the stage instead of going dead and leaving a
 * separate progress bar to explain itself.
 */
@Composable
private fun InstallActions(
    state: AppDetailUiState,
    actions: AppDetailActions,
    onUninstall: () -> Unit,
) {
    val app = state.app ?: return
    val stage = state.stage
    val busy = stage != null && stage !is InstallStage.Failed

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                busy -> Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                    Text(stageLabel(stage))
                }

                state.hasUpdate -> {
                    Button(onClick = { actions.install(app) }, modifier = Modifier.weight(1f)) {
                        IconDownload()
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_update))
                    }
                    FilledTonalButton(onClick = { actions.openApp(app.packageName) }) {
                        Text(stringResource(R.string.open))
                    }
                }

                state.isInstalled -> {
                    FilledTonalButton(
                        onClick = { actions.openApp(app.packageName) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.open))
                    }
                    OutlinedButton(onClick = onUninstall) { IconDelete() }
                }

                else -> Button(
                    onClick = { actions.install(app) },
                    modifier = Modifier.weight(1f),
                ) {
                    IconDownload()
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_install))
                }
            }
        }

        if (state.isInstalled && state.installedInfo?.versionName != null && !state.hasUpdate) {
            Text(
                stringResource(R.string.installed_2, state.installedInfo.versionName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        StageProgress(stage)
        if (stage is InstallStage.Failed) {
            TextButton(onClick = { actions.dismissInstallFailure(app.packageName) }) {
                Text(stringResource(R.string.action_dismiss))
            }
        }
    }
}

/** Screenshots, at a fixed height so mixed aspect ratios still scroll as one strip. */
@Composable
private fun ScreenshotStrip(urls: List<String>) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(urls, key = { it }) { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Summary always, full description behind a toggle — most of them are very long. */
@Composable
private fun Description(app: UnifiedApp) {
    if (app.summary.isBlank() && app.description.isBlank()) return
    var expanded by remember(app.packageName) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (app.summary.isNotBlank()) {
            Text(
                app.summary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        if (app.description.isNotBlank() && app.description != app.summary) {
            Text(
                app.description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(
                        if (expanded) R.string.action_show_less else R.string.action_read_more
                    )
                )
                Spacer(Modifier.width(4.dp))
                if (expanded) IconKeyboardArrowUp() else IconKeyboardArrowDown()
            }
        }
    }
}

@Composable
private fun WhatsNew(app: UnifiedApp) {
    val notes = app.whatsNew?.takeIf { it.isNotBlank() } ?: return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.detail_whats_new),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        app.updatedOn?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(notes, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Chips(app: UnifiedApp) {
    if (app.categories.isEmpty() && app.antiFeatures.isEmpty() && !app.containsAds && !app.reproducible) return
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // F-Droid versions the verification server rebuilt bit-for-bit earn a positive badge.
        if (app.reproducible) {
            item("reproducible") { InfoChip(stringResource(R.string.chip_reproducible)) }
        }
        items(app.categories, key = { "cat-$it" }) { InfoChip(it) }
        if (app.containsAds) {
            item("ads") { InfoChip(stringResource(R.string.chip_contains_ads), emphasise = true) }
        }
        // F-Droid's anti-feature labels are the honest part of its listings and there is
        // no reason to bury them; they are flagged rather than hidden.
        items(app.antiFeatures, key = { "af-$it" }) { InfoChip(it, emphasise = true) }
    }
}

/**
 * What can be checked about this app's download, and what the last install proved.
 *
 * Neutral by design: the card describes the source's own practices and this app's checks,
 * and does not claim one source is safer than another. Tapping opens the full comparison.
 */
@Composable
private fun TrustCard(
    source: AppSource,
    verification: VerificationResult?,
    onOpenTrust: () -> Unit,
) {
    val profile = TrustProfile.of(source)
    Card(
        onClick = onOpenTrust,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(profile.title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(profile.summary), style = MaterialTheme.typography.bodySmall)
            when (verification) {
                is VerificationResult.Rejected -> Text(
                    stringResource(R.string.detail_install_blocked, verification.reason),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                is VerificationResult.Verified -> Text(
                    stringResource(R.string.detail_checked_on_install, verification.detail),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is VerificationResult.Unverified -> Text(
                    stringResource(R.string.detail_installed_unverified, verification.reason),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                null -> Text(
                    stringResource(R.string.detail_tap_for_trust),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Links(app: UnifiedApp, actions: AppDetailActions) {
    val links = buildList<Pair<Int, String>> {
        app.website?.let { add(R.string.link_website to it) }
        app.sourceCode?.let { add(R.string.link_source to it) }
        app.privacyPolicyUrl?.let { add(R.string.link_privacy to it) }
    }
    if (links.isEmpty() && app.source != AppSource.PLAYSTORE) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        links.forEach { (label, url) ->
            OutlinedButton(
                onClick = { actions.openInBrowser(url) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconGlobe()
                Spacer(Modifier.width(8.dp))
                Text(stringResource(label))
            }
        }
        if (app.source == AppSource.PLAYSTORE) {
            OutlinedButton(
                onClick = { actions.openInPlayStore(app.packageName) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.view_in_play_store))
            }
        }
    }
}

/** The remaining facts, as a plain table — deliberately last. */
@Composable
private fun Details(app: UnifiedApp) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HorizontalDivider()
        DetailRow(stringResource(R.string.detail_package), app.packageName)
        app.license?.let { DetailRow(stringResource(R.string.detail_license), it) }
        app.targetSdk?.let { DetailRow(stringResource(R.string.detail_target_sdk), it.toString()) }
        if (app.ratingCount > 0) {
            DetailRow(stringResource(R.string.detail_rating_count), formatCount(app.ratingCount))
        }
        if (app.permissions.isNotEmpty()) {
            DetailRow(
                stringResource(R.string.detail_permissions),
                app.permissions.joinToString("\n") { it.substringAfterLast('.') },
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
