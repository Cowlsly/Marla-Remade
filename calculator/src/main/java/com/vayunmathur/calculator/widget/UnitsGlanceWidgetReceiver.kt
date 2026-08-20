package com.vayunmathur.calculator.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.vayunmathur.library.widgets.updateWidgetPreviews

/**
 * No periodic update is scheduled: a conversion only changes when the user changes it, and both
 * sides push an update when they do — the converter through `CalculatorViewModel`, the widget
 * through its own action callbacks.
 */
class UnitsGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: UnitsGlanceWidget = UnitsGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        context.updateWidgetPreviews(UnitsGlanceWidgetReceiver::class)
    }
}
