package com.vayunmathur.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.SleepSessionRecord
import com.vayunmathur.health.R
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.SleepData
import com.vayunmathur.health.data.SleepStage
import com.vayunmathur.health.ui.components.hypnogramColors
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.health.util.displayString
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepDetailsPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    val initialPage = 999
    val pagerState = rememberPagerState(initialPage = initialPage) { 1000 }
    val scope = rememberCoroutineScope()
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(tz)

    AppScaffold(
        title = {
            Text(
                stringResource(R.string.label_sleep),
                modifier = Modifier.sharedText("health-metric-label-SLEEP"),
            )
        },
        onNavigateBack = { backStack.pop() },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Navigation Row (Same as BarChartDetails)
            val selectedDay = remember(pagerState.currentPage) {
                today.minus(initialPage - pagerState.currentPage, DateTimeUnit.DAY)
            }
            val headerLabel = if (selectedDay == today) stringResource(R.string.label_today)
            else selectedDay.displayString()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    }) {
                    IconBack()
                }

                Text(
                    text = headerLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.widthIn(min = 140.dp),
                    textAlign = TextAlign.Center
                )

                IconButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    enabled = pagerState.currentPage < pagerState.pageCount - 1 && selectedDay < today
                ) {
                    IconArrowForward()
                }
            }

            HorizontalPager(
                state = pagerState, modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) { page ->
                val day = today.minus(initialPage - page, DateTimeUnit.DAY)
                val record by remember(day) { viewModel.sleepRecordForDay(day) }
                    .collectAsState(null)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    record?.let { r ->
                        val sleepData = r.sleepData
                        if (sleepData != null) {
                            item { SleepSummaryHeader(r) }
                            item { SleepStageGraph(r) }
                            item { SleepStageBreakdown(sleepData) }
                        }
                    } ?: item {
                        Box(
                            Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.no_data_available),
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SleepSummaryHeader(record: Record) {
    val context = LocalContext.current
    val totalMinutes = (record.value * 60).toLong()
    val startT = record.startTime.atZone(java.time.ZoneId.systemDefault())
    val endT = record.endTime.atZone(java.time.ZoneId.systemDefault())
    val is24 = rememberIs24Hour()

    Column {
        Text(
            text = hoursMinutesString(context, totalMinutes),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Light,
            color = HealthColors.Sleep,
        )
        Text(
            text = "${DateString.time(LocalTime(startT.hour, startT.minute), is24)} - ${DateString.time(LocalTime(endT.hour, endT.minute), is24)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
fun SleepStageGraph(record: Record) {
    val stages = remember(record) {
        record.sleepData?.stagesJson?.let { Json.decodeFromString<List<SleepStage>>(it) }
            ?: emptyList()
    }

    if (stages.isEmpty()) return

    val startTime = record.startTime.toEpochMilli()
    val endTime = record.endTime.toEpochMilli()
    val duration = (endTime - startTime).coerceAtLeast(1)

    val colors = hypnogramColors()
    val awakeColor = colors.awake
    val remColor = colors.rem
    val coreColor = colors.core
    val deepColor = colors.deep

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.height(160.dp)
            ) {
                Text(
                    stringResource(R.string.label_awake),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    stringResource(R.string.label_rem),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    stringResource(R.string.label_core),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    stringResource(R.string.label_deep),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            val gridColor = MaterialTheme.colorScheme.outlineVariant
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(160.dp)
                    .padding(start = 8.dp)
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val levelHeight = canvasHeight / 4

                // Draw background lines
                for (i in 0..4) {
                    val y = i * levelHeight
                    drawLine(
                        gridColor,
                        Offset(0f, y),
                        Offset(canvasWidth, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                stages.forEach { stage ->
                    val startX =
                        ((stage.startTimeMillis - startTime).toFloat() / duration) * canvasWidth
                    val endX =
                        ((stage.endTimeMillis - startTime).toFloat() / duration) * canvasWidth

                    val (yPos, color) = when (stage.stage) {
                        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> 0f to awakeColor
                        SleepSessionRecord.STAGE_TYPE_REM -> levelHeight to remColor
                        SleepSessionRecord.STAGE_TYPE_LIGHT -> levelHeight * 2 to coreColor
                        SleepSessionRecord.STAGE_TYPE_DEEP -> levelHeight * 3 to deepColor
                        else -> levelHeight * 2 to coreColor
                    }

                    drawRoundRect(
                        color = color, topLeft = Offset(startX, yPos + 4.dp.toPx()), size = Size(
                            (endX - startX).coerceAtLeast(2.dp.toPx()), levelHeight - 8.dp.toPx()
                        ), cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Time labels
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val startT = record.startTime.atZone(java.time.ZoneId.systemDefault())
            val endT = record.endTime.atZone(java.time.ZoneId.systemDefault())
            val is24 = rememberIs24Hour()

            Text(
                DateString.hourLabel(startT.hour, is24),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                DateString.hourLabel(endT.hour, is24),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun SleepStageBreakdown(data: SleepData) {
    val context = LocalContext.current
    val colors = hypnogramColors()
    DashboardSection(title = "Stages", accentColor = HealthColors.Sleep) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StageRow(
                stringResource(R.string.label_awake),
                hoursMinutesString(context, data.awakeDurationMillis / 60000),
                colors.awake
            )
            StageRow(
                stringResource(R.string.label_rem),
                hoursMinutesString(context, data.remDurationMillis / 60000),
                colors.rem
            )
            StageRow(
                stringResource(R.string.label_core),
                hoursMinutesString(context, data.lightDurationMillis / 60000),
                colors.core
            )
            StageRow(
                stringResource(R.string.label_deep),
                hoursMinutesString(context, data.deepDurationMillis / 60000),
                colors.deep
            )
        }
    }
}

@Composable
fun StageRow(label: String, duration: String, color: Color) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(12.dp), shape = MaterialTheme.shapes.extraSmall, color = color) {}
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Text(duration, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}
