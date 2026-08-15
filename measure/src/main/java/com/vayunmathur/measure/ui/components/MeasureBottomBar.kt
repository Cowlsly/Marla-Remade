package com.vayunmathur.measure.ui.pages

import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.IconArea
import com.vayunmathur.library.ui.IconCompass
import com.vayunmathur.library.ui.IconRuler
import com.vayunmathur.library.ui.IconToolsLevel
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.measure.Route

/** The four tools, as one tab bar shared by every top-level page. */
@Composable
fun MeasureBottomBar(backStack: NavBackStack<Route>, current: Route) {
    BottomNavBar(
        backStack = backStack,
        pages = listOf(
            BottomBarItem("Compass", Route.Compass) { IconCompass() },
            BottomBarItem("Level", Route.Level) { IconToolsLevel() },
            BottomBarItem("Ruler", Route.Ruler) { IconRuler() },
            BottomBarItem("Measure", Route.ArMeasure) { IconArea() },
        ),
        currentPage = current,
    )
}
