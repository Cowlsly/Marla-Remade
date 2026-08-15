package com.vayunmathur.clock.ui

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.library.ui.IconAccessTime
import com.vayunmathur.library.ui.IconAlarm
import com.vayunmathur.library.ui.IconHourglass
import com.vayunmathur.library.ui.IconTimer
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack

@Composable
fun ClockTabs(
    backStack: NavBackStack<Route>,
    ds: DataStoreUtils,
    clockViewModel: ClockViewModel,
    initialTab: Int = 0,
) {
    val pagerState = rememberPagerState(initialPage = initialTab, pageCount = { 4 })
    val tabs = listOf(
        PagerTab(stringResource(R.string.label_alarm), { IconAlarm() }) {
            AlarmPage(backStack, clockViewModel)
        },
        PagerTab(stringResource(R.string.label_clock), { IconAccessTime() }) {
            ClockPage(backStack, ds, clockViewModel)
        },
        PagerTab(stringResource(R.string.label_timer), { IconHourglass() }) {
            TimerPage(backStack, clockViewModel)
        },
        PagerTab(stringResource(R.string.label_stopwatch), { IconTimer() }) {
            StopwatchPage(backStack, clockViewModel)
        },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}