package com.vayunmathur.measure

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.measure.platform.MeasureViewModel
import com.vayunmathur.measure.ui.ArMeasurePage
import com.vayunmathur.measure.ui.CompassPage
import com.vayunmathur.measure.ui.DiagnosticsPage
import com.vayunmathur.measure.ui.LevelPage
import com.vayunmathur.measure.ui.RulerPage
import com.vayunmathur.measure.ui.SavedMeasurementsPage
import com.vayunmathur.measure.ui.SettingsPage

@Composable
fun Navigation(viewModel: MeasureViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Compass)
    backStack.openSettingsIfRequested(Route.Settings)
    MainNavigation(backStack) {
        entry<Route.Compass> { CompassPage(backStack, viewModel) }
        entry<Route.Level> { LevelPage(backStack, viewModel) }
        entry<Route.Ruler> { RulerPage(backStack, viewModel) }
        entry<Route.ArMeasure> { ArMeasurePage(backStack, viewModel) }
        entry<Route.Saved> { SavedMeasurementsPage(backStack, viewModel) }
        entry<Route.Settings>(metadata = DialogPage()) { SettingsPage(backStack, viewModel) }
        entry<Route.Diagnostics>(metadata = DialogPage()) { DiagnosticsPage(backStack, viewModel) }
    }
}
