package com.vayunmathur.appstore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.ModernAppsRepo
import com.vayunmathur.appstore.data.accrescent.AccrescentRepo
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The source list is fixed — see [DefaultRepos] for why nothing can be added to it. What the
 * user does control is which of them the store talks to at all, and this page is where that
 * choice is made. It also shows what each source is pinned to, so the pins can be compared
 * against their published values rather than taken on faith.
 */
@Composable
fun SourcesPage(
    viewModel: AppStoreViewModel,
    onBack: () -> Unit,
    onOpenTrust: () -> Unit = {},
) {
    val repos by viewModel.repos.collectAsState()
    val home by viewModel.home.collectAsState()
    val autoInstallUpdates by viewModel.autoInstallUpdates.collectAsState()
    val enabledSources by viewModel.enabledSources.collectAsState()
    val fdroid = repos.find { it.url == DefaultRepos.FDROID.url }
    val noneEnabled = AppSource.TOGGLEABLE.none { it in enabledSources }
    // Sync only refreshes the two offline indexes; with both off it has nothing to fetch.
    val nothingToSync = DefaultRepos.ALL.none { it.source in enabledSources }

    DetailScaffold(
        title = stringResource(R.string.repositories),
        onNavigateBack = onBack,
        alignment = AppBarAlignment.Center,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        Text(
            stringResource(R.string.sources_are_fixed),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.syncSources() },
                enabled = !home.isSyncing && !nothingToSync,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        if (home.isSyncing) R.string.repos_syncing
                        else R.string.repos_sync_sources
                    )
                )
            }
            OutlinedButton(onClick = onOpenTrust, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.trust_page_title))
            }
        }
        if (noneEnabled) {
            Text(
                stringResource(R.string.sources_all_disabled),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (home.statusMessage.isNotBlank()) {
            Text(
                home.statusMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        Card(Modifier.fillMaxWidth()) {
            SettingsSwitchRow(
                title = stringResource(R.string.setting_auto_install_updates_title),
                supportingText = stringResource(R.string.setting_auto_install_updates_summary),
                checked = autoInstallUpdates,
                onCheckedChange = { viewModel.setAutoInstallUpdates(it) },
            )
        }

        SourceCard(
            title = stringResource(R.string.source_modern_apps),
            subtitle = ModernAppsRepo.PROJECT_URL,
            pinLabel = stringResource(R.string.source_modern_apps_pin),
            pins = viewModel.ownSigningCertificates,
            lastSync = 0L,
            enabled = AppSource.MODERN_APPS in enabledSources,
            onEnabledChange = { viewModel.setSourceEnabled(AppSource.MODERN_APPS, it) },
        )
        SourceCard(
            title = stringResource(R.string.source_fdroid),
            subtitle = DefaultRepos.FDROID.url,
            pinLabel = stringResource(R.string.source_fdroid_pin),
            pins = setOfNotNull(
                fdroid?.fingerprint ?: DefaultRepos.FDROID.pinnedFingerprint
            ),
            lastSync = fdroid?.lastSync ?: 0L,
            enabled = AppSource.FDROID in enabledSources,
            onEnabledChange = { viewModel.setSourceEnabled(AppSource.FDROID, it) },
        )
        SourceCard(
            title = stringResource(R.string.source_play),
            subtitle = "play.google.com",
            pinLabel = stringResource(R.string.source_play_pin),
            pins = emptySet(),
            lastSync = 0L,
            enabled = AppSource.PLAYSTORE in enabledSources,
            onEnabledChange = { viewModel.setSourceEnabled(AppSource.PLAYSTORE, it) },
        )
        SourceCard(
            title = stringResource(R.string.source_accrescent),
            subtitle = AccrescentRepo.REPOSITORY_URL,
            pinLabel = stringResource(R.string.source_accrescent_pin),
            pins = setOf(AccrescentRepo.REPODATA_PUBKEY),
            lastSync = 0L,
            enabled = AppSource.ACCRESCENT in enabledSources,
            onEnabledChange = { viewModel.setSourceEnabled(AppSource.ACCRESCENT, it) },
            abbreviatePins = false,
        )
    }
}

@Composable
private fun SourceCard(
    title: String,
    subtitle: String,
    pinLabel: String,
    pins: Set<String>,
    lastSync: Long,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    /**
     * Whether [pins] are hex certificate fingerprints to abbreviate for display. Accrescent's
     * pin is a base64 signify ed25519 key, not a hex fingerprint, so it is shown verbatim.
     */
    abbreviatePins: Boolean = true,
) {
    val locale = LocalConfiguration.current.locales[0]
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                pinLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            pins.forEach {
                Text(
                    if (abbreviatePins) ApkCertificates.abbreviate(it) else it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (lastSync > 0) {
                Text(
                    stringResource(
                        R.string.last_sync,
                        SimpleDateFormat("yyyy-MM-dd HH:mm", locale)
                            .format(Date(lastSync)),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
