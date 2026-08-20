package com.vayunmathur.calculator

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.calculator.ui.CalculatorPage
import com.vayunmathur.calculator.ui.GraphPage
import com.vayunmathur.calculator.ui.UnitConverterPage
import com.vayunmathur.calculator.util.CalculatorViewModel
import com.vayunmathur.calculator.widget.CalculatorGlanceWidgetReceiver
import com.vayunmathur.calculator.widget.UnitsGlanceWidget
import com.vayunmathur.calculator.widget.UnitsGlanceWidgetReceiver
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.IconCalculate
import com.vayunmathur.library.ui.IconFunctions
import com.vayunmathur.library.ui.IconRuler
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.widgets.updateWidgetPreviews
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val viewModel: CalculatorViewModel by viewModels()

    /**
     * Counts units deep-links rather than latching a flag: the widget can be tapped again after
     * the user has manually moved to another tab, and an unchanged flag would not re-navigate.
     */
    private var unitsRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FIRST_PARTY covers api.vayunmathur.com, where the currency-rate proxy lives.
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        updateWidgetPreviews(CalculatorGlanceWidgetReceiver::class)
        updateWidgetPreviews(UnitsGlanceWidgetReceiver::class)
        enableEdgeToEdge()
        if (intent.opensUnits()) unitsRequest++
        setContent {
            DynamicTheme {
                Navigation(viewModel, unitsRequest)
            }
        }
    }

    /**
     * Where widget taps land once the app is running: the units widget's intent carries
     * `FLAG_ACTIVITY_SINGLE_TOP`, and this activity is always top of its own task.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.opensUnits()) unitsRequest++
    }
}

private fun Intent?.opensUnits(): Boolean =
    this?.getBooleanExtra(UnitsGlanceWidget.EXTRA_OPEN_UNITS, false) == true

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Calculator : Route

    @Serializable
    data object Graph : Route

    @Serializable
    data object Units : Route
}

@Composable
fun Navigation(viewModel: CalculatorViewModel, unitsRequest: Int = 0) {
    val backStack = rememberNavBackStack<Route>(if (unitsRequest > 0) Route.Units else Route.Calculator)
    LaunchedEffect(unitsRequest) {
        if (unitsRequest > 0 && backStack.last() != Route.Units) backStack.reset(Route.Units)
    }
    val current = backStack.last()
    MainNavigation(
        backStack,
        bottomBar = {
            BottomNavBar(
                backStack,
                listOf(
                    BottomBarItem("Calculator", Route.Calculator) { IconCalculate() },
                    BottomBarItem("Graph", Route.Graph) { IconFunctions() },
                    BottomBarItem("Units", Route.Units) { IconRuler() },
                ),
                current,
            )
        },
    ) {
        entry<Route.Calculator> { CalculatorPage(viewModel) }
        entry<Route.Graph> { GraphPage(viewModel) }
        entry<Route.Units> { UnitConverterPage(viewModel) }
    }
}
