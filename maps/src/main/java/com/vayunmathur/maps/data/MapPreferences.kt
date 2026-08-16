package com.vayunmathur.maps.data

/**
 * Central registry of the maps app's DataStore preference keys and their small
 * enum value types (P6). Kept in one place so the settings screen, the layers
 * sheet, `MainActivity` (theme) and `NavigationService` (voice) all agree on the
 * exact key strings and defaults instead of scattering magic strings.
 */
object MapPreferences {
    const val KEY_VOICE_GUIDANCE = "voice_guidance_enabled"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_DISTANCE_UNITS = "distance_units"

    // Layer toggles surfaced in the P6 layers sheet.
    const val KEY_LAYER_TRAFFIC = "layer_traffic"
    const val KEY_LAYER_SATELLITE = "layer_satellite"
    const val KEY_LAYER_SAFETY = "layer_safety"

    const val DEFAULT_VOICE_GUIDANCE = true
    const val DEFAULT_LAYER_TRAFFIC = true
    const val DEFAULT_LAYER_SATELLITE = false
    const val DEFAULT_LAYER_SAFETY = false
}

/** App theme selection persisted under [MapPreferences.KEY_THEME_MODE]. */
enum class ThemeMode(val pref: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    /** null = follow the system (what [com.vayunmathur.library.ui.DynamicTheme] expects). */
    val darkOverride: Boolean?
        get() = when (this) {
            SYSTEM -> null
            LIGHT -> false
            DARK -> true
        }

    companion object {
        fun from(pref: String?): ThemeMode = entries.firstOrNull { it.pref == pref } ?: SYSTEM
    }
}

/** Distance unit selection persisted under [MapPreferences.KEY_DISTANCE_UNITS]. */
enum class DistanceUnit(val pref: String) {
    KILOMETERS("km"),
    MILES("mi");

    companion object {
        fun from(pref: String?): DistanceUnit = entries.firstOrNull { it.pref == pref } ?: KILOMETERS
    }
}
