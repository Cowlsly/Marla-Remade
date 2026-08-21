package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.BottomNavBarItem
import kotlinx.coroutines.launch

/** One tab in a [TabbedPagerScaffold]: a label, an optional icon, and its page. */
data class PagerTab(
    val label: String,
    val icon: (@Composable () -> Unit)? = null,
    val content: @Composable () -> Unit,
)

/** How [TabbedPagerScaffold] presents its tabs. */
enum class TabStyle {
    /** A [BottomNavBar] at the bottom - the app-level "main tabs" case (music). */
    BottomNav,

    /** A [PrimaryTabRow] under the top bar - the in-screen tabs case. */
    PrimaryTabRow,
}

/**
 * A tab host that swipes: a set of [tabs] shown in a [HorizontalPager], kept in
 * sync with either a bottom navigation bar or a top tab row.
 *
 * Music and calendar had written out the same plumbing - a
 * [rememberCoroutineScope] used only to `animateScrollToPage`, the pager, and
 * the fiddly business of forwarding just the bottom inset so an inner page's own
 * top bar is not pushed down. That is all this owns; app-specific pagers (a
 * date-anchored calendar, say) stay hand-written.
 *
 * [leadingBottomBar] sits above the tab bar in [TabStyle.BottomNav] (music's
 * now-playing controls) and is the plain bottom bar in [TabStyle.PrimaryTabRow].
 */
@Composable
fun TabbedPagerScaffold(
    tabs: List<PagerTab>,
    modifier: Modifier = Modifier,
    pagerState: PagerState = rememberPagerState(pageCount = { tabs.size }),
    tabStyle: TabStyle = TabStyle.BottomNav,
    topBar: @Composable () -> Unit = {},
    leadingBottomBar: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val select: (Int) -> Unit = { index ->
        if (pagerState.currentPage != index) scope.launch { pagerState.animateScrollToPage(index) }
    }

    when (tabStyle) {
        TabStyle.BottomNav -> Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = {
                Column(Modifier.fillMaxWidth()) {
                    leadingBottomBar()
                    BottomNavBar {
                        tabs.forEachIndexed { index, tab ->
                            BottomNavBarItem(
                                selected = pagerState.currentPage == index,
                                onClick = { select(index) },
                                icon = tab.icon ?: {},
                                label = tab.label,
                            )
                        }
                    }
                }
            },
        ) { padding ->
            // Inner pages own their top bar and consume the top inset, so only
            // the bottom space this scaffold's bars take is forwarded - adding
            // the full padding here would inset the status bar twice. The
            // forwarded space has to be consumed too: the bars already cover
            // the navigation bar, so a page's own scaffold would otherwise
            // inset for it again and leave a gap above the tab bar.
            val bottom = padding.calculateBottomPadding()
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .padding(bottom = bottom)
                    .consumeWindowInsets(PaddingValues(bottom = bottom)),
            ) { page -> tabs[page].content() }
        }

        TabStyle.PrimaryTabRow -> Scaffold(
            modifier = modifier,
            topBar = {
                Column(Modifier.fillMaxWidth()) {
                    topBar()
                    PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                        tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { select(index) },
                                text = { Text(tab.label) },
                                icon = tab.icon,
                            )
                        }
                    }
                }
            },
            bottomBar = leadingBottomBar,
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.padding(padding),
            ) { page -> tabs[page].content() }
        }
    }
}

/**
 * A lightweight, non-swiping tab row - the "product tabs" look (travel). For swipeable page
 * hosting use [TabbedPagerScaffold].
 *
 * A [ButtonGroup] rather than a row of chips: the choices are one control, so they should read
 * as one object. The group also owns its own overflow menu, which is why this no longer needs
 * to scroll horizontally - a label that does not fit becomes a menu entry instead of scrolling
 * off the edge where it is easy to miss.
 */
@Composable
fun ChipTabRow(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    ButtonGroup(modifier = modifier.padding(horizontal = Spacing.lg)) {
        tabs.forEachIndexed { index, label ->
            toggleableItem(
                checked = selected == index,
                label = label,
                onCheckedChange = { onSelect(index) },
            )
        }
    }
}
