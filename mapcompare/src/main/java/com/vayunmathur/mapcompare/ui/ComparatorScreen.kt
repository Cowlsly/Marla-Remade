package com.vayunmathur.mapcompare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.MapStyle
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import org.maplibre.compose.camera.CameraState as MlCameraState
import org.maplibre.compose.camera.rememberCameraState as mlRememberCameraState
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position as MlPosition
import org.maplibre.compose.camera.CameraPosition as MlCameraPosition

@Composable
fun ComparatorScreen(archivePath: String? = null, pickProbe: Boolean = false) {
    val context = LocalContext.current

    var currentPreset by remember { mutableStateOf(PRESETS.first()) }
    val vulkanCamera = rememberCameraState(
        CameraPosition(GeoPoint(currentPreset.lon, currentPreset.lat), currentPreset.zoom)
    )
    val mlCamera: MlCameraState = mlRememberCameraState(
        MlCameraPosition(target = MlPosition(currentPreset.lon, currentPreset.lat), zoom = currentPreset.zoom)
    )

    var mapStyleJson by remember { mutableStateOf<String?>(null) }
    var styleError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val raw = context.assets.open("style.json").bufferedReader().readText()
            mapStyleJson = raw
        } catch (e: Exception) {
            styleError = e.message
        }
    }

    LaunchedEffect(currentPreset) {
        val p = currentPreset
        vulkanCamera.animateTo(CameraPosition(GeoPoint(p.lon, p.lat), p.zoom))
        mlCamera.animateTo(MlCameraPosition(target = MlPosition(p.lon, p.lat), zoom = p.zoom))
    }

    AppScaffold(
        title = "Map Compare  ${currentPreset.label}",
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(PRESETS, key = { it.label }) { preset ->
                    val selected = preset == currentPreset
                    FilterChip(
                        selected = selected,
                        onClick = { currentPreset = preset },
                        label = { Text(preset.label) },
                    )
                }
            }

            Row(Modifier.fillMaxWidth().height(28.dp)) {
                Box(
                    Modifier.weight(1f).fillMaxSize().background(Color(0xFFE9E7E2)),
                    contentAlignment = Alignment.Center,
                ) { Text("Vulkan  planet.mamaps", color = Color(0xFF444444)) }
                Box(Modifier.width(1.dp).fillMaxSize().background(Color(0xFF444444)))
                Box(
                    Modifier.weight(1f).fillMaxSize().background(Color(0xFFE0E0E0)),
                    contentAlignment = Alignment.Center,
                ) { Text("MapLibre  v4.pmtiles", color = Color(0xFF444444)) }
            }

            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxSize()) {
                    VectorMap(
                        cameraState = vulkanCamera,
                        style = MapStyle.Standard,
                        archivePath = archivePath,
                        modifier = Modifier.fillMaxSize(),
                        // TEMPORARY task-17 pick probe (remove with pickProbe flag):
                        // query placed labels around the tap and log the rows.
                        onMapClickWithScreen = if (pickProbe) {
                            { click ->
                                val projection = vulkanCamera.projection
                                if (projection == null) {
                                    android.util.Log.i("PickProbe", "tap NO-PROJECTION yet")
                                } else {
                                    val pad = 24.dp
                                    val box = androidx.compose.ui.unit.DpRect(
                                        click.screen.x - pad,
                                        click.screen.y - pad,
                                        click.screen.x + pad,
                                        click.screen.y + pad,
                                    )
                                    val hits = projection.queryRenderedLabels(box, emptySet())
                                    if (hits.isEmpty()) {
                                        android.util.Log.i(
                                            "PickProbe",
                                            "tap EMPTY at=${click.position.longitude},${click.position.latitude}",
                                        )
                                    } else {
                                        hits.forEach { h ->
                                            android.util.Log.i(
                                                "PickProbe",
                                                "tap HIT layerId=${h.layerId} name=${h.name} kind=${h.kind} " +
                                                    "lon=${h.position.longitude} lat=${h.position.latitude}",
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
                Box(Modifier.width(1.dp).fillMaxSize().background(Color(0xFF222222)))
                Box(Modifier.weight(1f).fillMaxSize()) {
                    when {
                        styleError != null -> Box(
                            Modifier.fillMaxSize().background(Color(0xFFFFEEEE)),
                            contentAlignment = Alignment.Center,
                        ) { Text("style load failed: $styleError") }
                        mapStyleJson == null -> Box(
                            Modifier.fillMaxSize().background(Color(0xFFEEEEEE)),
                            contentAlignment = Alignment.Center,
                        ) { Text("Loading style…") }
                        else -> MaplibreMap(
                            modifier = Modifier.fillMaxSize(),
                            baseStyle = BaseStyle.Json(mapStyleJson!!),
                            cameraState = mlCamera,
                            options = MapOptions(
                                RenderOptions(renderMode = RenderOptions.RenderMode.TextureView),
                                GestureOptions.Standard,
                                OrnamentOptions.AllDisabled,
                            ),
                        ) {}
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    val idx = PRESETS.indexOf(currentPreset)
                    currentPreset = PRESETS[(idx - 1 + PRESETS.size) % PRESETS.size]
                }, modifier = Modifier.weight(1f)) { Text("Prev") }
                Button(onClick = {
                    val idx = PRESETS.indexOf(currentPreset)
                    currentPreset = PRESETS[(idx + 1) % PRESETS.size]
                }, modifier = Modifier.weight(1f)) { Text("Next") }
            }
        }
    }
}
