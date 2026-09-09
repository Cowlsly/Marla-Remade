package com.vayunmathur.maps.ui.streetview

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.IconDirectionsWalk
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.PanoramaCrop
import com.vayunmathur.library.ui.PanoramaSphere
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBarOverlay
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.google.StreetViewDataSource
import com.vayunmathur.maps.data.google.StreetViewLink
import com.vayunmathur.maps.data.google.StreetViewPano
import kotlinx.coroutines.launch

/**
 * Full-screen Google Street View viewer.
 *
 * RENDERER: a Street View panorama is equirectangular, so it is projected onto the
 * inside of a sphere by the shared [PanoramaSphere] — the same GLES renderer the
 * photos app uses for 360 photos, lifted into `:library:ui`. Dragging looks around
 * and pinching changes the field of view; there is no flat image to pan.
 *
 * Tapping a neighbour steps to the adjacent pano ([StreetViewPano.neighbors]),
 * refetched by id, and the new panorama reloads its own texture.
 */
@Composable
fun StreetViewScreen(initialPano: StreetViewPano, onClose: () -> Unit) {
    var pano by remember { mutableStateOf(initialPano) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }

    // Load (and reload on pano change) the stitched equirect off the main thread.
    LaunchedEffect(pano.panoId) {
        loading = true
        image = null
        image = StreetViewDataSource.loadPanorama(pano)
        loading = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val current = image
        when {
            current != null -> PanoramaSphere(
                crop = PanoramaCrop.full(current.width, current.height),
                textureKey = pano.panoId,
                modifier = Modifier.fillMaxSize(),
            ) { current }
            loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            else -> Text(
                stringResource(R.string.street_view_unavailable),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        TopAppBarOverlay(Modifier.align(Alignment.TopStart), onNavigateBack = onClose)

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (pano.neighbors.isNotEmpty()) {
                NeighborSteps(pano.neighbors) { link -> stepTo(link) { pano = it } }
            }
            Attribution(pano)
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
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
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
