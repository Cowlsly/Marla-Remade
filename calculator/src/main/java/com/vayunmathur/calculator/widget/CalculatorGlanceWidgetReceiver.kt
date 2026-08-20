package com.vayunmathur.calculator.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.vayunmathur.library.widgets.updateWidgetPreviews

class CalculatorGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: CalculatorGlanceWidget = CalculatorGlanceWidget()

    // No periodic refresh: the keypad's content only changes when the user taps a key.
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        context.updateWidgetPreviews(CalculatorGlanceWidgetReceiver::class)
    }
}
