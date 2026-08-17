package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.MapPreferences
import com.vayunmathur.maps.data.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs both the P6 settings screen (units / theme / voice guidance) and the P6
 * layers sheet (traffic / satellite / safety toggles), reading and writing the
 * shared [MapPreferences] keys through [DataStoreUtils].
 *
 * The layer toggles live here (rather than on the map VM) so the same persisted
 * value drives both the sheet's switches and the layer visibility in
 * `MyMapLayers`. Reads use the boolean/string flows seeded with the current
 * snapshot so the first frame already reflects the stored value; writes are
 * fire-and-forget on `viewModelScope`.
 */
class MapSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val ds = DataStoreUtils.getInstance(application)

    // --- Settings screen ---------------------------------------------------

    val themeMode: StateFlow<ThemeMode> =
        ds.stringFlow(MapPreferences.KEY_THEME_MODE)
            .map { ThemeMode.from(it) }
            .stateIn(
                viewModelScope, SharingStarted.Eagerly,
                ThemeMode.from(ds.getString(MapPreferences.KEY_THEME_MODE)),
            )

    val voiceGuidance: StateFlow<Boolean> = boolPref(
        MapPreferences.KEY_VOICE_GUIDANCE, MapPreferences.DEFAULT_VOICE_GUIDANCE,
    )

    fun setThemeMode(mode: ThemeMode) =
        launch { ds.setString(MapPreferences.KEY_THEME_MODE, mode.pref) }

    fun setVoiceGuidance(enabled: Boolean) =
        launch { ds.setBoolean(MapPreferences.KEY_VOICE_GUIDANCE, enabled) }

    // --- Layers sheet ------------------------------------------------------

    val trafficLayer: StateFlow<Boolean> = boolPref(
        MapPreferences.KEY_LAYER_TRAFFIC, MapPreferences.DEFAULT_LAYER_TRAFFIC,
    )
    val satelliteLayer: StateFlow<Boolean> = boolPref(
        MapPreferences.KEY_LAYER_SATELLITE, MapPreferences.DEFAULT_LAYER_SATELLITE,
    )
    val safetyLayer: StateFlow<Boolean> = boolPref(
        MapPreferences.KEY_LAYER_SAFETY, MapPreferences.DEFAULT_LAYER_SAFETY,
    )
    val transitLayer: StateFlow<Boolean> = boolPref(
        MapPreferences.KEY_LAYER_TRANSIT, MapPreferences.DEFAULT_LAYER_TRANSIT,
    )

    fun setTrafficLayer(enabled: Boolean) =
        launch { ds.setBoolean(MapPreferences.KEY_LAYER_TRAFFIC, enabled) }

    fun setSatelliteLayer(enabled: Boolean) =
        launch { ds.setBoolean(MapPreferences.KEY_LAYER_SATELLITE, enabled) }

    fun setSafetyLayer(enabled: Boolean) =
        launch { ds.setBoolean(MapPreferences.KEY_LAYER_SAFETY, enabled) }

    fun setTransitLayer(enabled: Boolean) =
        launch { ds.setBoolean(MapPreferences.KEY_LAYER_TRANSIT, enabled) }

    private fun boolPref(key: String, default: Boolean): StateFlow<Boolean> =
        ds.booleanFlow(key).stateIn(
            viewModelScope, SharingStarted.Eagerly, ds.getBoolean(key, default),
        )

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
