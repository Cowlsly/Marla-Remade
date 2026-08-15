package com.vayunmathur.health.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconBedtime
import com.vayunmathur.library.ui.IconDirectionsWalk
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.ui.components.ActivityRingsTrio
import com.vayunmathur.health.ui.components.MetricRow
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.DashboardSectionDivider
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.health.util.MainPageMetrics
import com.vayunmathur.health.util.TodayActions
import com.vayunmathur.health.util.TodayUiState
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.round
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** Binds [HealthViewModel] and the back stack to the stateless [TodayScreen]. */
@Composable
fun TodayPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val today = now.date

    val dayRange = remember(today) {
        val start = today.atStartOfDayIn(TimeZone.currentSystemDefault())
        start to start.plus(24.hours)
    }
    val dayStart = dayRange.first
    val dayEnd = dayRange.second

    val stepsToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Steps, dayStart, dayEnd).map { it.toLong() }
    }.collectAsState(0L)
    val activeCaloriesToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.CaloriesActive, dayStart, dayEnd).map { it.toLong() }
    }.collectAsState(0L)
    val mindfulnessToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Mindfulness, dayStart, dayEnd).map { it.toLong() }
    }.collectAsState(0L)
    val distanceToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Distance, dayStart, dayEnd)
    }.collectAsState(0.0)
    val floorsToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Floors, dayStart, dayEnd)
    }.collectAsState(0.0)
    val hydrationToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Hydration, dayStart, dayEnd)
    }.collectAsState(0.0)
    val hrMax by remember(dayStart, dayEnd) {
        viewModel.maxInRange(RecordType.HeartRate, dayStart, dayEnd).map { it?.toLong() ?: 0L }
    }.collectAsState(0L)
    val hrMin by remember(dayStart, dayEnd) {
        viewModel.minInRange(RecordType.HeartRate, dayStart, dayEnd).map { it?.toLong() ?: 0L }
    }.collectAsState(0L)

    val metrics: MainPageMetrics by viewModel.mainPageMetrics.collectAsState()

    LaunchedEffect(today) {
        viewModel.loadMainPageMetrics()
    }

    TodayScreen(
        state = TodayUiState(
            steps = stepsToday,
            activeCalories = activeCaloriesToday,
            mindfulnessMinutes = mindfulnessToday,
            distanceKm = distanceToday,
            floors = floorsToday,
            hydrationMl = hydrationToday,
            heartRateMin = hrMin,
            heartRateMax = hrMax,
            metrics = metrics,
        ),
        actions = object : TodayActions {
            override fun openSleepDetails() {
                backStack.add(Route.SleepDetails)
            }

            override fun openMetric(config: HealthMetricConfig) {
                backStack.add(Route.BarChartDetails(config))
            }
        },
    )
}

/**
 * The Today screen, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(state: TodayUiState, actions: TodayActions) {
    Scaffold { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ActivityRingsHero(
                    stepsToday = state.steps,
                    activeCaloriesToday = state.activeCalories,
                    mindfulnessToday = state.mindfulnessMinutes,
                )
            }

            // Last night
            item {
                DashboardSection(
                    title = stringResource(R.string.section_last_night),
                    accentColor = HealthColors.Sleep,
                ) {
                    val sleepValue = state.metrics.sleepMinutes?.let { formatSleep(it) } ?: "--"
                    MetricRow(
                        label = stringResource(R.string.label_sleep),
                        value = sleepValue,
                        unit = "",
                        leadingIcon = { m, c -> IconBedtime(m, c) },
                        leadingTint = HealthColors.Sleep,
                        onClick = { actions.openSleepDetails() },
                    )
                }
            }

            // Latest vitals
            item {
                DashboardSection(
                    title = stringResource(R.string.section_latest_vitals),
                    accentColor = HealthColors.Vitals,
                ) {
                    MetricRow(
                        label = stringResource(R.string.label_heart_rate),
                        value = if (state.heartRateMax > 0L) "${state.heartRateMin}-${state.heartRateMax}" else "--",
                        unit = stringResource(R.string.unit_bpm),
                        leadingIcon = { m, c -> IconFavorite(m, c) },
                        leadingTint = colorFor(RecordType.HeartRate),
                        onClick = { actions.openMetric(HealthMetricConfig.HEART_RATE) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_blood_pressure),
                        value = state.metrics.bloodPressure?.let { "${it.first.toInt()}/${it.second.toInt()}" } ?: "--",
                        unit = stringResource(R.string.label_blood_pressure_unit),
                        leadingIcon = { m, c -> IconFavorite(m, c) },
                        leadingTint = colorFor(RecordType.BloodPressure),
                        onClick = { actions.openMetric(HealthMetricConfig.BLOOD_PRESSURE) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_oxygen_saturation),
                        value = state.metrics.spo2?.round(1)?.toString() ?: "--",
                        unit = stringResource(R.string.unit_percent),
                        leadingIcon = { m, c -> IconFavorite(m, c) },
                        leadingTint = colorFor(RecordType.OxygenSaturation),
                        onClick = { actions.openMetric(HealthMetricConfig.OXYGEN_SATURATION) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_resting_heart_rate),
                        value = state.metrics.rhr?.toString() ?: "--",
                        unit = stringResource(R.string.unit_bpm),
                        leadingIcon = { m, c -> IconFavorite(m, c) },
                        leadingTint = colorFor(RecordType.RestingHeartRate),
                        onClick = { actions.openMetric(HealthMetricConfig.RESTING_HEART_RATE) },
                    )
                }
            }

            // Quick stats
            item {
                DashboardSection(
                    title = stringResource(R.string.section_quick_stats),
                    accentColor = HealthColors.Activity,
                ) {
                    MetricRow(
                        label = stringResource(R.string.label_steps),
                        value = state.steps.toString(),
                        unit = stringResource(R.string.unit_steps),
                        leadingIcon = { m, c -> IconDirectionsWalk(m, c) },
                        leadingTint = colorFor(RecordType.Steps),
                        onClick = { actions.openMetric(HealthMetricConfig.STEPS) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_distance),
                        value = state.distanceKm.round(2).toString(),
                        unit = stringResource(R.string.unit_km),
                        leadingIcon = { m, c -> IconLocationOn(m, c) },
                        leadingTint = colorFor(RecordType.Distance),
                        onClick = { actions.openMetric(HealthMetricConfig.DISTANCE) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_floors),
                        value = state.floors.round(1).toString(),
                        unit = stringResource(R.string.unit_fl),
                        leadingIcon = { m, c -> IconLocationOn(m, c) },
                        leadingTint = colorFor(RecordType.Floors),
                        onClick = { actions.openMetric(HealthMetricConfig.FLOORS) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_hydration),
                        value = state.hydrationMl.toInt().toString(),
                        unit = stringResource(R.string.unit_ml),
                        leadingIcon = { m, c -> IconBedtime(m, c) }, // TODO: replace with a water-drop icon
                        leadingTint = hydrationColor,
                        onClick = { actions.openMetric(HealthMetricConfig.HYDRATION) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityRingsHero(
    stepsToday: Long,
    activeCaloriesToday: Long,
    mindfulnessToday: Long,
) {
    val stepsGoal = HealthMetricConfig.STEPS.dailyGoal
    val energyGoal = HealthMetricConfig.ACTIVE_CALORIES.dailyGoal
    val mindfulGoal = 20.0
    val stepsPct = (stepsToday.toFloat() / stepsGoal.toFloat()).coerceIn(0f, 1f)
    val energyPct = (activeCaloriesToday.toFloat() / energyGoal.toFloat()).coerceIn(0f, 1f)
    val mindfulPct = (mindfulnessToday.toFloat() / mindfulGoal.toFloat()).coerceIn(0f, 1f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier.size(140.dp),
                contentAlignment = Alignment.Center,
            ) {
                ActivityRingsTrio(
                    stepsPct = stepsPct,
                    energyPct = energyPct,
                    mindfulPct = mindfulPct,
                    modifier = Modifier.size(140.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RingLegendItem(
                    color = HealthColors.Activity,
                    label = stringResource(R.string.label_steps),
                    value = "$stepsToday",
                    unit = stringResource(R.string.unit_steps),
                )
                RingLegendItem(
                    color = HealthColors.Nutrition,
                    label = stringResource(R.string.label_active),
                    value = "$activeCaloriesToday",
                    unit = stringResource(R.string.unit_cal),
                )
                RingLegendItem(
                    color = HealthColors.Sleep,
                    label = stringResource(R.string.label_mindfulness),
                    value = "$mindfulnessToday",
                    unit = stringResource(R.string.unit_min),
                )
            }
        }
    }
}

@Composable
private fun RingLegendItem(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    value: String,
    unit: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(10.dp),
            shape = CircleShape,
            color = color,
            content = {},
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

private fun formatSleep(minutes: Long): String = com.vayunmathur.health.util.formatDuration(minutes)
