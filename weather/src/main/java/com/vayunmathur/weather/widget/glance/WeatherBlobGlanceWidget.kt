package com.vayunmathur.weather.glance

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vayunmathur.library.ui.MaterialShapes
import com.vayunmathur.library.widgets.DynamicThemeGlance
import com.vayunmathur.library.widgets.toWidgetBitmap
import com.vayunmathur.weather.MainActivity
import com.vayunmathur.weather.R
import com.vayunmathur.weather.data.WeatherRepository
import com.vayunmathur.weather.data.weatherJson
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.util.roundCoord
import com.vayunmathur.weather.util.weatherConditionForCode
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Blob weather widget matching the screenshot reference: a dark teal [MaterialShapes.Pill] —
 * the Material 3 shape with round caps at the bottom-left and top-right joined by straight
 * edges — carrying a large mint temperature and a white condition icon.
 * 2x2 — floats over the wallpaper, not full-bleed rect.
 */
class WeatherBlobGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val weather = loadWeatherSnapshot(context)
        provideContent {
            DynamicThemeGlance(context) {
                BlobContent(weather)
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        try {
            provideContent {
                DynamicThemeGlance(context) {
                    BlobPreviewContent()
                }
            }
        } catch (e: Throwable) {
            Log.e("WeatherBlobWidget", "providePreview failed", e)
        }
    }

    private suspend fun loadWeatherSnapshot(context: Context): WidgetWeather? {
        return try {
            val repo = WeatherRepository.get(context)
            val location = repo.getCurrentDeviceLocation()
                ?: repo.getLocations().firstOrNull()
                ?: return null
            val cache = repo.getCache(
                roundCoord(location.latitude),
                roundCoord(location.longitude),
            ) ?: return null
            val forecast = runCatching {
                weatherJson.decodeFromString<ForecastResponse>(cache.forecastJson)
            }.getOrNull() ?: return null
            val current = forecast.current ?: return null
            WidgetWeather(
                temperatureCelsius = current.temperature,
                weatherCode = current.weatherCode,
                isDay = current.isDay != 0,
            )
        } catch (_: Exception) {
            null
        }
    }
}

// Screenshot colors: dark teal blob, light mint temperature.
private val BlobColor = Color(0xFF162E2B)
private val BlobTempColor = Color(0xFFBFE8E1)

/**
 * The blob is drawn into a bitmap sized to the widget so its edges stay crisp. Capped because a
 * RemoteViews payload has a hard size budget, and floored so a tiny reported size (previews, a
 * host that has not measured yet) still gives a smooth outline.
 */
private const val MIN_BLOB_PX = 128
private const val MAX_BLOB_PX = 512

@Composable
private fun BlobContent(weather: WidgetWeather?) {
    val context = LocalContext.current

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(
                actionStartActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        // Box in Glance stacks children (last on top): blob behind, temp+icon over it.
        BlobBackground()
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BlobWeatherBlock(weather, BlobTempColor)
        }
    }
}

@Composable
private fun BlobPreviewContent() {
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        BlobBackground()
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BlobWeatherBlock(
                WidgetWeather(temperatureCelsius = 34.0, weatherCode = 3, isDay = true),
                BlobTempColor
            )
        }
    }
}

@Composable
private fun BlobBackground() {
    val context = LocalContext.current
    val size = LocalSize.current
    val density = context.resources.displayMetrics.density
    val sizePx = (max(size.width.value, size.height.value) * density)
        .roundToInt()
        .coerceIn(MIN_BLOB_PX, MAX_BLOB_PX)
    val blob = remember(sizePx) { MaterialShapes.Pill.toWidgetBitmap(sizePx, BlobColor.toArgb()) }
    Image(
        provider = ImageProvider(blob),
        contentDescription = null,
        modifier = GlanceModifier.fillMaxSize()
    )
}

@Composable
private fun BlobWeatherBlock(weather: WidgetWeather?, tempColor: Color) {
    val context = LocalContext.current

    if (weather == null) {
        Text(
            text = "—",
            style = TextStyle(
                color = ColorProvider(tempColor),
                fontSize = 54.sp,
                fontWeight = FontWeight.Bold
            )
        )
        return
    }

    val condition = weatherConditionForCode(weather.weatherCode)

    // Large temp — screenshot shows 34° very prominent, light mint color
    Text(
        text = context.getString(R.string.weather_temp_format, weather.temperatureCelsius.roundToInt()),
        style = TextStyle(
            color = ColorProvider(tempColor),
            fontSize = 54.sp,
            fontWeight = FontWeight.Medium
        )
    )

    // Condition icon below temp — white, small, slightly overlapped like screenshot
    Image(
        provider = ImageProvider(condition.iconRes(weather.isDay)),
        contentDescription = context.getString(condition.label),
        modifier = GlanceModifier.size(40.dp).padding(top = 4.dp)
    )
}
