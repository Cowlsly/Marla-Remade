package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconDirectionsWalk
import com.vayunmathur.library.ui.IconFire
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.ui.components.MetricRow
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.DashboardSectionDivider
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.library.util.round
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
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
    val distanceToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Distance, dayStart, dayEnd)
    }.collectAsState(0.0)
    val floorsToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Floors, dayStart, dayEnd)
    }.collectAsState(0.0)
    val elevationToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Elevation, dayStart, dayEnd)
    }.collectAsState(0.0)
    val wheelchairToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Wheelchair, dayStart, dayEnd).map { it.toLong() }
    }.collectAsState(0L)
    val exerciseToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Exercise, dayStart, dayEnd).map { it.toLong() }
    }.collectAsState(0L)

    Scaffold { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Today
            item {
                DashboardSection(
                    title = stringResource(R.string.section_today_activity),
                    accentColor = HealthColors.Activity,
                ) {
                    MetricRow(
                        label = stringResource(R.string.label_steps),
                        value = stepsToday.toString(),
                        unit = stringResource(R.string.unit_steps),
                        leadingIcon = { m, c -> IconDirectionsWalk(m, c) },
                        leadingTint = colorFor(RecordType.Steps),
                        labelModifier = Modifier.sharedText("health-metric-label-STEPS"),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.STEPS)) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_active),
                        value = activeCaloriesToday.toString(),
                        unit = stringResource(R.string.unit_cal),
                        leadingIcon = { m, c -> IconFire(m, c) },
                        leadingTint = colorFor(RecordType.CaloriesActive),
                        labelModifier = Modifier.sharedText("health-metric-label-ACTIVE_CALORIES"),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.ACTIVE_CALORIES)) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_distance),
                        value = distanceToday.round(2).toString(),
                        unit = stringResource(R.string.unit_km),
                        leadingIcon = { m, c -> IconLocationOn(m, c) },
                        leadingTint = colorFor(RecordType.Distance),
                        labelModifier = Modifier.sharedText("health-metric-label-DISTANCE"),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.DISTANCE)) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_floors),
                        value = floorsToday.round(1).toString(),
                        unit = stringResource(R.string.unit_fl),
                        leadingIcon = { m, c -> IconLocationOn(m, c) },
                        leadingTint = colorFor(RecordType.Floors),
                        labelModifier = Modifier.sharedText("health-metric-label-FLOORS"),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.FLOORS)) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_elevation),
                        value = elevationToday.round(1).toString(),
                        unit = stringResource(R.string.unit_m),
                        leadingIcon = { m, c -> IconLocationOn(m, c) },
                        leadingTint = colorFor(RecordType.Elevation),
                        labelModifier = Modifier.sharedText("health-metric-label-ELEVATION"),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.ELEVATION)) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_wheelchair_pushes),
                        value = wheelchairToday.toString(),
                        unit = stringResource(R.string.unit_pushes),
                        leadingIcon = { m, c -> IconDirectionsWalk(m, c) },
                        leadingTint = colorFor(RecordType.Wheelchair),
                        labelModifier = Modifier.sharedText("health-metric-label-WHEELCHAIR_PUSHES"),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.WHEELCHAIR_PUSHES)) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_exercise),
                        value = exerciseToday.toString(),
                        unit = stringResource(R.string.unit_min),
                        leadingIcon = { m, c -> IconDirectionsWalk(m, c) },
                        leadingTint = colorFor(RecordType.Exercise),
                        labelModifier = Modifier.sharedText("health-metric-label-EXERCISE"),
                        onClick = { backStack.add(Route.ExerciseDetails) },
                    )
                }
            }

            // This week (sparklines stubbed — TODO wire HealthAPI.getListOfSums for 7-day arrays)
            item {
                DashboardSection(
                    title = stringResource(R.string.section_this_week),
                    accentColor = HealthColors.Activity,
                ) {
                    MetricRow(
                        label = stringResource(R.string.label_steps),
                        value = stepsToday.toString(),
                        unit = stringResource(R.string.unit_steps),
                        leadingIcon = { m, c -> IconDirectionsWalk(m, c) },
                        leadingTint = colorFor(RecordType.Steps),
                        sparkline = emptyList(), // TODO: HealthAPI.getListOfSums for 7d
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.STEPS)) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_active),
                        value = activeCaloriesToday.toString(),
                        unit = stringResource(R.string.unit_cal),
                        leadingIcon = { m, c -> IconFire(m, c) },
                        leadingTint = colorFor(RecordType.CaloriesActive),
                        sparkline = emptyList(), // TODO
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.ACTIVE_CALORIES)) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_distance),
                        value = distanceToday.round(2).toString(),
                        unit = stringResource(R.string.unit_km),
                        leadingIcon = { m, c -> IconLocationOn(m, c) },
                        leadingTint = colorFor(RecordType.Distance),
                        sparkline = emptyList(), // TODO
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.DISTANCE)) },
                    )
                }
            }
        }
    }
}
