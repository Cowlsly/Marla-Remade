package com.vayunmathur.measure.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconArea
import com.vayunmathur.library.ui.IconCompass
import com.vayunmathur.library.ui.IconRuler
import com.vayunmathur.library.ui.IconToolsLevel
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.measure.R
import com.vayunmathur.measure.Route

/** The four tools, as one tab bar shared by every top-level page. */
@Composable
fun MeasureBottomBar(backStack: NavBackStack<Route>, current: Route) {
    BottomNavBar(
        backStack = backStack,
        pages = listOf(
            BottomBarItem(stringResource(R.string.tool_compass), Route.Compass) { IconCompass() },
            BottomBarItem(stringResource(R.string.tool_level), Route.Level) { IconToolsLevel() },
            BottomBarItem(stringResource(R.string.tool_ruler), Route.Ruler) { IconRuler() },
            BottomBarItem(stringResource(R.string.tool_measure), Route.ArMeasure) { IconArea() },
        ),
        currentPage = current,
    )
}
