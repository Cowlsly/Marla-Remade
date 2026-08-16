package com.vayunmathur.maps.ui.streetview

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDirectionsWalk
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.google.StreetViewDataSource
import com.vayunmathur.maps.data.google.StreetViewLink
import com.vayunmathur.maps.data.google.StreetViewPano

/**
 * Full-screen Google Street View viewer.
 *
 * RENDERER: the equirectangular panorama is a single stitched [Bitmap] shown with
 * the **photos app's pan/zoom image renderer** — the manual pinch/pan pattern
 * replicated from `photos/.../ui/PhotoPage.kt`'s `PhotoDetailView` (an
 * `awaitEachGesture` loop driving a `graphicsLayer` scale/translation, plus a
 * double-tap zoom toggle). This is NOT Vela's GLES sphere; it's a flat pannable
 * image, chosen for simplicity and consistency with the photos viewer.
 *
 * Tapping a neighbour steps to the adjacent pano ([StreetViewPano.neighbors]),
 * refetched by id, and the zoom resets for the new image.
 */
@Composable
fun StreetViewScreen(initialPano: StreetViewPano, onClose: () -> Unit) {
    var pano by remember { mutableStateOf(initialPano) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember { mutableStateOf(true) }

    // Load (and reload on pano change) the stitched equirect off the main thread.
    LaunchedEffect(pano.panoId) {
        loading = true
        image = null
        val bmp: Bitmap? = StreetViewDataSource.loadPanorama(pano)
        image = bmp?.asImageBitmap()
        loading = false
    }

    Scaffold(containerColor = Color.Black) { padding ->
        Box(Modifier.fillMaxSize()) {
            val current = image
            when {
                current != null -> ZoomablePanorama(current, key = pano.panoId)
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                else -> Text(
                    stringResource(R.string.street_view_unavailable),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).padding(padding).padding(8.dp),
            ) {
                IconClose(tint = Color.White)
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(padding).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pano.neighbors.isNotEmpty()) {
                    NeighborSteps(pano.neighbors) { link -> stepTo(link) { pano = it } }
                }
                Attribution(pano)
            }
        }
    }
}

/** Load a neighbour pano and, if it resolves, swap to it (else keep the current). */
private suspend fun stepTo(link: StreetViewLink, onResolved: (StreetViewPano) -> Unit) {
    StreetViewDataSource.byPano(link.panoId)?.let(onResolved)
}

@Composable
private fun NeighborSteps(neighbors: List<StreetViewLink>, onStep: suspend (StreetViewLink) -> Unit) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        neighbors.forEach { link ->
            FilledTonalButton(onClick = { scope.launch { onStep(link) } }) {
                IconDirectionsWalk(Modifier.size(18.dp))
                Text(
                    "  ${compass(link.bearingDeg)} · ${link.distanceM.toInt()} m",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun Attribution(pano: StreetViewPano) {
    val date = if (pano.captureYear != null && pano.captureMonth != null) {
        " · ${pano.captureYear}-${pano.captureMonth.toString().padStart(2, '0')}"
    } else {
        ""
    }
    Text(
        (pano.copyright ?: "\u00A9 Google") + date,
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall,
    )
}

/** Compass point (8-wind) for a bearing in degrees. */
private fun compass(bearingDeg: Double): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[(((bearingDeg % 360 + 360) % 360) / 45.0).toInt() % 8]
}

/**
 * The photos-app pan/zoom renderer, replicated: a Compose [Image] transformed by a
 * hoisted scale/offset that a raw `awaitEachGesture` pinch/pan loop drives, with a
 * double-tap zoom toggle. Pan is clamped to the image bounds; zoom is 1×..5×. Not
 * hosted in a pager, so gestures are always consumed once zoomed/pinching. Zoom
 * state is keyed on [key] so it resets when the panorama changes.
 */
@Composable
private fun ZoomablePanorama(image: ImageBitmap, key: Any) {
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(key) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val isPinching = zoomChange != 1f
                        val isZoomed = scale > 1.01f
                        if (isZoomed || isPinching) {
                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                            if (newScale > 1f) {
                                val maxX = size.width * (newScale - 1) / 2
                                val maxY = size.height * (newScale - 1) / 2
                                val next = offset + panChange
                                scale = newScale
                                offset = Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
                            } else {
                                scale = 1f
                                offset = Offset.Zero
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
                .background(Color.Black)
                .onGloballyPositioned { size = it.size }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
        )
    }
}
