package com.vayunmathur.health.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.data.RecordType.*

/** Six category buckets shared by every metric / page accent in the app. */
object HealthColors {
    val Activity = Color(0xFF43A047)   // green
    val Vitals = Color(0xFFE53935)     // red
    val Body = Color(0xFF00897B)       // teal
    val Sleep = Color(0xFF5C6BC0)      // indigo
    val Nutrition = Color(0xFFFB8C00)  // orange
    val Hydration = Color(0xFF039BE5)  // blue
    val Medical = Color(0xFF8E24AA)    // purple — vaccinations and medication
}

/** Macro accents used in NutritionPage. */
val proteinColor = Color(0xFFEC407A)   // pink
val carbsColor = Color(0xFFFFB300)     // amber
val fatColor = Color(0xFF9CCC65)       // yellow-green
val hydrationColor = HealthColors.Hydration

@Composable @ReadOnlyComposable
fun colorFor(metric: RecordType): Color = when (metric) {
    Steps, Distance, Floors, Elevation, Wheelchair,
    CaloriesActive, CaloriesTotal, CaloriesBasal, Exercise -> HealthColors.Activity
    HeartRate, RestingHeartRate, HeartRateVariabilityRmssd, RespiratoryRate,
    OxygenSaturation, BloodPressure, BloodGlucose, Vo2Max, SkinTemperature -> HealthColors.Vitals
    Weight, Height, BodyFat, LeanBodyMass, BoneMass, BodyWaterMass -> HealthColors.Body
    Sleep, Mindfulness -> HealthColors.Sleep
    Nutrition -> HealthColors.Nutrition
    Hydration -> HealthColors.Hydration
}

@Composable @ReadOnlyComposable
fun colorFor(config: HealthMetricConfig): Color = colorFor(config.recordType)
