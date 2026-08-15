package com.vayunmathur.weather.glance

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.vayunmathur.library.widgets.scheduleHourlyUpdate
import com.vayunmathur.library.widgets.updateWidgetPreviews
import com.vayunmathur.weather.data.WeatherRefreshWorker

class WeatherBlobGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: WeatherBlobGlanceWidget = WeatherBlobGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        context.scheduleHourlyUpdate(WeatherBlobGlanceWidget::class)
        WeatherRefreshWorker.scheduleHourlyRefresh(context)
        context.updateWidgetPreviews(WeatherBlobGlanceWidgetReceiver::class)
    }
}
