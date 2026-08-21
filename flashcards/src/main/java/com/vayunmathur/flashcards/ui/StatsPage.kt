package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.data.CardState
import com.vayunmathur.flashcards.util.DailyStat
import com.vayunmathur.flashcards.util.DeckOption
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.flashcards.util.StatsActions
import com.vayunmathur.flashcards.util.StatsUiState
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SecondaryTabRow
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import java.time.LocalDate

/** Binds review history for the selected deck to the stateless [StatsScreen]. */
@Composable
fun StatsPage(backStack: NavBackStack<Route>, viewModel: FlashcardsViewModel) {
    val decks by viewModel.decks.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    var selectedDeckId by remember { mutableStateOf<Long?>(null) }
    val logs by remember(selectedDeckId) { viewModel.reviewLogsFor(selectedDeckId) }
        .collectAsStateWithLifecycle(emptyList())

    val deckOptions = buildList {
        add(DeckOption(null, stringResource(R.string.all_decks)))
        decks.sortedBy { it.position }.forEach { add(DeckOption(it.id, it.name)) }
    }

    val zone = java.time.ZoneId.systemDefault()
    val byDay = logs.groupBy {
        java.time.Instant.ofEpochMilli(it.reviewedAt).atZone(zone).toLocalDate().toEpochDay()
    }
    val daily = byDay.map { (day, entries) -> DailyStat(day, entries.size) }.sortedBy { it.epochDay }

    // Retention over genuine recall attempts (cards previously seen).
    val recalls = logs.filter { it.elapsedDays > 0 }
    val retentionPct = if (recalls.isEmpty()) 0 else {
        (recalls.count { it.grade > 1 } * 100) / recalls.size
    }

    val today = LocalDate.now().toEpochDay()
    var streak = 0
    while (byDay.containsKey(today - streak)) streak++

    val deckCards = if (selectedDeckId == null) cards else cards.filter { it.deckId == selectedDeckId }

    // Cards becoming due per day over the next 30 days (overdue lumped into today).
    val forecast = (0 until 30).map { offset ->
        val day = today + offset
        val count = deckCards.count { card ->
            if (card.isNew || card.isSuspended) return@count false
            val due = java.time.Instant.ofEpochMilli(card.dueDate).atZone(zone).toLocalDate().toEpochDay()
            if (offset == 0) due <= day else due == day
        }
        DailyStat(day, count)
    }

    // Pass rate bucketed by the elapsed interval at review time.
    val buckets = listOf(
        "≤1" to 1.0,
        "2–3" to 3.0,
        "4–7" to 7.0,
        "8–14" to 14.0,
        "15–30" to 30.0,
        "31+" to Double.MAX_VALUE,
    )
    var lower = 0.0
    val retentionBuckets = buckets.map { (label, upper) ->
        val inBucket = recalls.filter { it.elapsedDays > lower && it.elapsedDays <= upper }
        lower = upper
        val rate = if (inBucket.isEmpty()) 0f else inBucket.count { it.grade > 1 }.toFloat() / inBucket.size
        label to rate
    }

    StatsScreen(
        state = StatsUiState(
            deckOptions = deckOptions,
            selectedDeckId = selectedDeckId,
            daily = daily,
            totalReviews = logs.size,
            retentionPct = retentionPct,
            streakDays = streak,
            matureCards = deckCards.count { it.state == CardState.REVIEW },
            totalCards = deckCards.size,
            forecast = forecast,
            retentionBuckets = retentionBuckets,
            heatmap = daily,
        ),
        actions = object : StatsActions {
            override fun back() { backStack.pop() }
            override fun selectDeck(id: Long?) { selectedDeckId = id }
        },
    )
}

/** Statistics for the selected deck. ViewModel-free so previews can render it. */
@Composable
fun StatsScreen(state: StatsUiState, actions: StatsActions) {
    var period by remember { mutableIntStateOf(1) }
    val windowDays = when (period) {
        0 -> 7
        1 -> 30
        else -> 365
    }

    AppScaffold(
        title = stringResource(R.string.nav_stats),
        onNavigateBack = { actions.back() },
        scrollBehavior = appBarScrollBehavior(),
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.deckOptions.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.deckOptions.forEach { option ->
                        FilterChip(
                            selected = option.id == state.selectedDeckId,
                            onClick = { actions.selectDeck(option.id) },
                            label = { Text(option.name) },
                        )
                    }
                }
            }

            SummaryRow(state)

            SecondaryTabRow(selectedTabIndex = period) {
                PeriodTab(R.string.period_week, 0, period) { period = 0 }
                PeriodTab(R.string.period_month, 1, period) { period = 1 }
                PeriodTab(R.string.period_year, 2, period) { period = 2 }
            }

            if (state.totalReviews == 0) {
                EmptyState(
                    title = stringResource(R.string.no_reviews),
                    message = stringResource(R.string.no_reviews_hint),
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                )
            } else {
                ReviewBarChart(
                    data = windowedData(state.daily, windowDays),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }

            ChartSection(stringResource(R.string.stat_heatmap)) {
                ReviewHeatmap(
                    days = state.heatmap,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            if (state.forecast.any { it.count > 0 }) {
                ChartSection(stringResource(R.string.stat_forecast)) {
                    ReviewBarChart(
                        data = state.forecast.map {
                            LocalDate.ofEpochDay(it.epochDay).dayOfMonth.toString() to it.count.toDouble()
                        },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }

            if (state.retentionBuckets.any { it.second > 0f }) {
                ChartSection(stringResource(R.string.stat_retention_by_interval)) {
                    ReviewBarChart(
                        data = state.retentionBuckets.map { it.first to (it.second * 100).toDouble() },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
    )
    content()
}

@Composable
private fun SummaryRow(state: StatsUiState) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MetricRing(
            progress = state.retentionPct / 100f,
            label = stringResource(R.string.stat_retention),
            value = "${state.retentionPct}%",
            modifier = Modifier.size(84.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatLine(stringResource(R.string.stat_reviews), state.totalReviews.toString())
            StatLine(stringResource(R.string.stat_streak), stringResource(R.string.days_value, state.streakDays))
            StatLine(
                stringResource(R.string.stat_mature),
                "${state.matureCards} / ${state.totalCards}",
            )
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PeriodTab(labelRes: Int, index: Int, selected: Int, onClick: () -> Unit) {
    Tab(
        selected = selected == index,
        onClick = onClick,
        text = { Text(stringResource(labelRes)) },
    )
}

private fun windowedData(daily: List<DailyStat>, windowDays: Int): List<Pair<String, Double>> {
    val counts = daily.associate { it.epochDay to it.count }
    val today = LocalDate.now().toEpochDay()
    return (0 until windowDays).map { offset ->
        val day = today - (windowDays - 1 - offset)
        val date = LocalDate.ofEpochDay(day)
        date.dayOfMonth.toString() to (counts[day] ?: 0).toDouble()
    }
}
