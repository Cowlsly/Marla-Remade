package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable

/**
 * Safety / road-furniture data source (P13). The features are baked into the
 * overlay PMTiles archive (source-layer [SOURCE_LAYER]).
 *
 * Each `safety` feature carries a `kind` in [KIND_SPEED_CAMERA],
 * [KIND_ALPR], [KIND_SURVEILLANCE], [KIND_STOP_SIGN], [KIND_TRAFFIC_SIGNALS].
 */
object SafetyLayersSource {
    /** Baked into the overlay PMTiles. */
    const val SOURCE_LAYER: String = "safety"
    const val LAYER_ID: String = "safety-icons"

    const val KIND_SPEED_CAMERA = "speed_camera"
    const val KIND_ALPR = "alpr"
    const val KIND_SURVEILLANCE = "surveillance"
    const val KIND_STOP_SIGN = "stop_sign"
    const val KIND_TRAFFIC_SIGNALS = "traffic_signals"

    /** Whether the overlay tileset exists. The pin rendering additionally needs renderer
     *  vector-layer support (see [SafetyLayer]) before anything draws. */
    val available: Boolean get() = true
}

/**
 * Mount the safety-layers overlay when [enabled] (the P6 LayersSheet toggle).
 *
 * Currently a NO-OP (renderer gap, reported to lead — library:map has no vector-layer API,
 * so the `safety` source-layer cannot be drawn). Kept so the toggle wiring and the
 * [SafetyLayersSource] contract survive the swap untouched.
 */
@Composable
fun SafetyLayer(enabled: Boolean) {
    if (!enabled || !SafetyLayersSource.available) return
    // Intentionally empty until the renderer can draw vector source-layers.
}
