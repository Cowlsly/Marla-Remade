package com.vayunmathur.maps.ui.map.style

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.maps.util.MapTileCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The basemap style, which is not available on the first frame.
 *
 * Modelled explicitly because the map used to render nothing at all while the style loaded —
 * a blank screen that is indistinguishable from a broken one.
 */
sealed interface MapStyle {
    data object Loading : MapStyle
    data class Ready(val json: String) : MapStyle
}

/**
 * Read `style.json` and patch it for the current theme.
 *
 * Keyed on `(hybridUrl, isDark)`: the asset read and the patch together walk a 3544-line style,
 * so doing it on every recomposition would be wasteful, and doing it on the main thread would
 * be worse. [produceState] restarts only when one of those actually changes, which for the
 * theme means once per flip rather than once per frame of the flip's animation.
 */
@Composable
fun rememberMapStyle(
    isDark: Boolean,
    hybridUrl: String = MapTileCache.BASEMAP_PMTILES_URL,
    context: Context = LocalContext.current,
): State<MapStyle> = produceState<MapStyle>(MapStyle.Loading, hybridUrl, isDark) {
    value = MapStyle.Ready(
        withContext(Dispatchers.IO) {
            val rawStyle = context.assets.open("style.json").bufferedReader().readText()
            patchStyleForHybrid(
                rawStyle,
                MapTileCache.BASEMAP_PMTILES_URL,
                hybridUrl,
                isDark,
            )
        }
    )
}
