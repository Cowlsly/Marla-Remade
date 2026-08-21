package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExtendedFloatingActionButton
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCompass
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SmallFloatingActionButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.R
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.maps.data.PostedLimit
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.ui.nav.ArrivalSummary
import com.vayunmathur.maps.ui.nav.ManeuverBanner
import com.vayunmathur.maps.ui.nav.SpeedWidget
import com.vayunmathur.maps.ui.nav.StepsSheet
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.formatDistance
import com.vayunmathur.maps.util.formatEta

/**
 * Full-screen overlay drawn on top of [MaplibreMap] while navigation is
 * active. Renders the Vela-style rich driving UI on top of MA's existing nav
 * session: a maneuver banner (with lane guidance + route shields), a
 * speedometer with posted-limit badge, an expandable step list, the bottom ETA
 * strip, nav controls (recenter + north-up compass toggle), and an
 * arrival/failure card for terminal states.
 *
 * Hidden when state is [NavigationSessionManager.NavState.Idle].
 */
@Composable
fun NavigationOverlay(
    navState: NavigationSessionManager.NavState,
    steps: List<RouteService.Step>,
    autoFollow: Boolean,
    onRecenter: () -> Unit,
    onEndTrip: () -> Unit,
    onDismissArrival: () -> Unit,
    postedLimit: PostedLimit? = null,
    northUp: Boolean = false,
    onToggleNorthUp: () -> Unit = {},
    destinationName: String? = null,
    darkBasemap: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (navState is NavigationSessionManager.NavState.Idle) return

    var showSteps by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        // ---- Top maneuver banner / status ----
        when (navState) {
            is NavigationSessionManager.NavState.Navigating -> {
                ManeuverBanner(
                    progress = navState.progress,
                    steps = steps,
                    modifier = navTopSlot(),
                )
            }
            NavigationSessionManager.NavState.Starting -> {
                StatusCard(
                    text = stringResource(R.string.nav_status_starting),
                    showProgress = true,
                    modifier = navTopSlot(),
                )
            }
            NavigationSessionManager.NavState.Recalculating -> {
                StatusCard(
                    text = stringResource(R.string.nav_off_route),
                    showProgress = true,
                    modifier = navTopSlot(),
                )
            }
            is NavigationSessionManager.NavState.Failed -> {
                FailureCard(
                    reason = navState.reason,
                    onDismiss = onDismissArrival,
                    modifier = navTopSlot(),
                )
            }
            else -> {}
        }

        // ---- Right-edge nav controls (compass north-up toggle + recenter) ----
        if (navState is NavigationSessionManager.NavState.Navigating) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(MapChromeMetrics.chromeMargin),
                verticalArrangement = Arrangement.spacedBy(MapChromeMetrics.fabSpacing),
                horizontalAlignment = Alignment.End,
            ) {
                SmallFloatingActionButton(onClick = onToggleNorthUp) {
                    IconCompass(
                        tint = if (northUp) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (!autoFollow) {
                    ExtendedFloatingActionButton(
                        onClick = onRecenter,
                        icon = { IconLocationOn() },
                        text = { Text(stringResource(R.string.nav_action_recenter)) },
                    )
                }
            }
        }

        // ---- Speedometer + posted-limit badge (bottom-left, above ETA) ----
        if (navState is NavigationSessionManager.NavState.Navigating) {
            SpeedWidget(
                speedMps = navState.progress.speedMps,
                postedLimit = postedLimit,
                darkBasemap = darkBasemap,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(
                        start = MapChromeMetrics.chromeMargin,
                        bottom = MapChromeMetrics.aboveEtaStrip,
                    ),
            )
        }

        // ---- Expandable full step list ----
        if (showSteps && navState is NavigationSessionManager.NavState.Navigating) {
            StepsSheet(
                steps = steps,
                currentStepIndex = navState.progress.currentStepIndex,
                onClose = { showSteps = false },
                // Insets before the clearance padding, matching the speedometer above. The
                // two were in opposite orders, which shifted them apart by the nav-bar
                // height on top of the clearance they already disagreed on.
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(bottom = MapChromeMetrics.aboveEtaStripTight),
            )
        }

        // ---- Bottom ETA strip / arrival card ----
        when (navState) {
            is NavigationSessionManager.NavState.Navigating -> {
                EtaStrip(
                    progress = navState.progress,
                    onEndTrip = onEndTrip,
                    onToggleSteps = { showSteps = !showSteps },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.systemBars),
                )
            }
            NavigationSessionManager.NavState.Arrived -> {
                ArrivalSummary(
                    onDismiss = onDismissArrival,
                    destinationName = destinationName,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(MapChromeMetrics.chromeMargin),
                )
            }
            else -> {}
        }
    }
}

/**
 * The top status slot: whichever single card the nav state calls for, tucked under the status
 * bar. One function because all four callers want byte-identical geometry — they were four
 * copies of the same chain, which is how they would have drifted apart.
 */
@Composable
private fun BoxScope.navTopSlot(): Modifier = Modifier
    .align(Alignment.TopCenter)
    .windowInsetsPadding(WindowInsets.statusBars)
    .padding(horizontal = Spacing.md, vertical = Spacing.sm)

@Composable
private fun StatusCard(
    text: String,
    showProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, fontWeight = FontWeight.Medium)
            if (showProgress) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EtaStrip(
    progress: NavigationProgress,
    onEndTrip: () -> Unit,
    onToggleSteps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val etaText = formatEta(progress.etaEpochMs)
    val remainingDistance = formatDistance(progress.distanceRemaining)
    val remainingMinutes = (((progress.etaEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)) / 60_000L).toInt()

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(etaText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.min, remainingMinutes, remainingDistance),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleSteps) { IconList() }
            Spacer(Modifier.height(0.dp))
            Button(
                onClick = onEndTrip,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(stringResource(R.string.nav_action_end))
            }
        }
    }
}

@Composable
private fun FailureCard(
    reason: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.nav_status_failed),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(reason)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(onClick = onDismiss) {
                    Text(stringResource(R.string.nav_arrived_dismiss))
                }
            }
        }
    }
}
