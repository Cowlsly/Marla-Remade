package com.vayunmathur.measure.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalResources
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.measure.Route
import com.vayunmathur.measure.domain.Units
import com.vayunmathur.measure.platform.MeasureViewModel
import com.vayunmathur.measure.ui.components.MeasureBottomBar

@Composable
fun RulerPage(backStack: NavBackStack<Route>, viewModel: MeasureViewModel) {
    val state by viewModel.ruler.collectAsState()
    val resources = LocalResources.current

    // ydpi is the display's reported *physical* vertical pixel density, which is a
    // better basis for a real-world scale than density * 160 — the latter is a
    // rendering bucket rounded to a standard step, not a measurement.
    LaunchedEffect(resources) {
        val metrics = resources.displayMetrics
        val dpi = metrics.ydpi.takeIf { it > 1f } ?: (metrics.density * 160f)
        viewModel.setPixelsPerMm(dpi / Units.MM_PER_INCH.toFloat())
    }

    RulerContent(
        state = state,
        onOpenSettings = { backStack.add(Route.Settings) },
        bottomBar = { MeasureBottomBar(backStack, Route.Ruler) },
    )
}
