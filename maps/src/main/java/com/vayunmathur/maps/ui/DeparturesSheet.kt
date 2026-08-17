package com.vayunmathur.maps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.util.DeparturesState
import kotlinx.coroutines.delay

/**
 * Public-transit departure board (P10): a live board for one stop, opened by
 * tapping a transit-stop pin. Departures are grouped by line (each with its GTFS
 * route colour), show a client-side live countdown, and are coloured by delay
 * (on-time green, slightly late amber, very late / cancelled red). Uses the
 * shared [ModalBottomSheet] from `:library:ui` (no raw scaffold), mirroring
 * [ParkingSheet] / [LayersSheet].
 *
 * The board is ONLINE-ONLY (Transitous); a failed fetch degrades to the
 * "no departures" state rather than an error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeparturesSheet(
    state: DeparturesState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
) {
    val stop: TransitStop? = when (state) {
        is DeparturesState.Loading -> state.stop
        is DeparturesState.Loaded -> state.stop
        DeparturesState.Idle -> null
    }

    // Live countdown clock: recomputed every 15 s (minute-granularity display).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffectTicker { now = it }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stop?.name ?: stringResource(R.string.transit_departures_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.transit_departures_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh) {
                    IconRefresh()
                }
            }

            Spacer(Modifier.height(8.dp))

            when (state) {
                is DeparturesState.Loading, DeparturesState.Idle -> {
                    Text(
                        stringResource(R.string.transit_departures_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is DeparturesState.Loaded -> {
                    if (state.departures.isEmpty()) {
                        Text(
                            stringResource(R.string.transit_departures_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        DepartureList(state.departures, now)
                    }
                }
            }
        }
    }
}

/** Group departures by line (ordered by the soonest departure in each group)
 *  and render each as a coloured line badge + its upcoming trips. */
@Composable
private fun DepartureList(departures: List<Departure>, now: Long) {
    val groups = remember(departures) {
        departures
            .sortedBy { it.realtimeMillis }
            .groupBy { it.line }
            .entries
            .sortedBy { entry -> entry.value.minOfOrNull { it.realtimeMillis } ?: Long.MAX_VALUE }
    }

    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
        items(groups, key = { it.key }) { (line, trips) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                LineBadge(line, trips.first().routeColor)
                Spacer(Modifier.height(4.dp))
                trips.forEach { dep ->
                    DepartureRow(dep, now)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun LineBadge(line: String, routeColor: String?) {
    val bg = parseHexColor(routeColor) ?: MaterialTheme.colorScheme.primary
    Text(
        text = line.ifBlank { "—" },
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = onColorFor(bg),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun DepartureRow(dep: Departure, now: Long) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                dep.headsign.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (dep.cancelled) TextDecoration.LineThrough else null,
            )
            val platform = dep.platform
            if (platform != null) {
                Text(
                    stringResource(R.string.transit_platform, platform),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            if (dep.cancelled) {
                Text(
                    stringResource(R.string.transit_cancelled),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = DELAY_LATE,
                )
            } else {
                Text(
                    countdownText(dep.realtimeMillis, now),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = delayColor(dep.delayMinutes),
                )
                if (dep.realTime && dep.delayMinutes != 0) {
                    Text(
                        stringResource(R.string.transit_delay_minutes, dep.delayMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = delayColor(dep.delayMinutes),
                    )
                }
            }
        }
    }
}

/** Minutes until [target] as display text; "Now" once it's due/passed. */
@Composable
private fun countdownText(target: Long, now: Long): String {
    val minutes = Math.ceil((target - now) / 60_000.0).toInt()
    return if (minutes <= 0) stringResource(R.string.transit_now)
    else stringResource(R.string.transit_minutes, minutes)
}

private val DELAY_ON_TIME = Color(0xFF2E7D32)
private val DELAY_SLIGHT = Color(0xFFF9A825)
private val DELAY_LATE = Color(0xFFC62828)

/** On-time/early → green, 1–4 min late → amber, ≥5 late → red. */
private fun delayColor(delayMinutes: Int): Color = when {
    delayMinutes <= 0 -> DELAY_ON_TIME
    delayMinutes < 5 -> DELAY_SLIGHT
    else -> DELAY_LATE
}

/** Parse a 6-digit hex (with or without `#`) → [Color], or null. */
private fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return runCatching { Color(android.graphics.Color.parseColor("#" + hex.removePrefix("#"))) }.getOrNull()
}

/** Readable text colour (black/white) for a coloured badge background. */
private fun onColorFor(bg: Color): Color {
    val luminance = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
    return if (luminance > 0.6) Color.Black else Color.White
}

/** A once-per-15s ticker that reports the current epoch millis to [onTick]. */
@Composable
private fun LaunchedEffectTicker(onTick: (Long) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            onTick(System.currentTimeMillis())
            delay(15_000L)
        }
    }
}
