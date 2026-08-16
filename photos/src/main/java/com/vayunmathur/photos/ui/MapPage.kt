package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.util.PhotoMapViewModel
import kotlinx.coroutines.delay
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.RasterMap
import com.vayunmathur.library.map.TileSource
import com.vayunmathur.library.map.rememberCameraState

// Helper class to hold cluster data
data class MapCluster(
    val position: DpOffset,
    val coverPhoto: Photo,
    val allPhotos: List<Photo>,
    val count: Int,
)

@Composable
fun MapPage(
    backStack: NavBackStack<Route>,
    galleryViewModel: GalleryViewModel,
    photoMapViewModel: PhotoMapViewModel,
) {
    val photos by galleryViewModel.photos.collectAsState()

    // Prepare raw GPS positions
    val positions = remember(photos) {
        photos.filter { it.lat != null && it.long != null }
            .map { (it.lat!! to it.long!!) to it }
    }

    val cameraState = rememberCameraState()

    // Clusters managed by VM (CPU-bound generation on Dispatchers.Default)
    val generatedClusters by photoMapViewModel.generatedClusters.collectAsState()
    var clusters: List<MapCluster> by remember { mutableStateOf(listOf()) }
    var selectedCluster: MapCluster? by remember { mutableStateOf(null) }

    val dpsize = LocalWindowInfo.current.containerDpSize

    LaunchedEffect(photos) {
        cameraState.awaitProjection()
        while (true) {
            val projection = cameraState.projection ?: continue
            val rawLocations = positions.map { (gps, photo) ->
                val dpOffset = projection.screenLocationFromPosition(
                    GeoPoint(gps.second, gps.first)
                )
                dpOffset to photo
            }.filter { (dpOffset, _) ->
                dpOffset.x.value > 0 && dpOffset.y.value > 0 && dpOffset.x < dpsize.width && dpOffset.y < dpsize.height
            }
            photoMapViewModel.regenerateClusters(rawLocations, 50.dp)
            delay(200)
        }
    }

    LaunchedEffect(generatedClusters) {
        selectedCluster = selectedCluster?.let { current ->
            generatedClusters.find { current.coverPhoto.id in it.allPhotos.map(Photo::id) }
        }
    }

    // RAW SCAFFOLD EXCEPTION: full-bleed map main-nav page with only a bottom
    // NavigationBar and no top app bar. AppScaffold always renders a top app bar (which
    // would break the immersive map) and the body is not a LazyColumn.
    Scaffold(bottomBar = { NavigationBar(Route.Map, backStack) }) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize()) {
            RasterMap(
                cameraState = cameraState,
                tileSource = TileSource.CartoVoyager,
                onMapClick = {
                    selectedCluster = null
                },
                onFrame = {
                    val projection = cameraState.projection

                    if (projection != null) {
                        // FAST PATH: re-project existing clusters every frame to
                        // prevent markers from drifting when panning/zooming
                        // between re-clustering intervals.
                        val updatedClusters = generatedClusters.mapNotNull { cluster ->
                            val lat = cluster.coverPhoto.lat
                            val long = cluster.coverPhoto.long
                            val nc = if (lat != null && long != null) {
                                val newOffset = projection.screenLocationFromPosition(
                                    GeoPoint(long, lat)
                                )
                                cluster.copy(position = newOffset)
                            } else {
                                null
                            }
                            if (cluster == selectedCluster) {
                                selectedCluster = nc
                            }
                            nc
                        }
                        clusters = updatedClusters
                    }
                }
            )

            // Layer: Markers
            Box(Modifier.fillMaxSize()) {
                clusters.forEach { cluster ->
                    Box(
                        Modifier
                            .offset(cluster.position.x, cluster.position.y)
                            .size(50.dp)
                            .background(Color.White, shape = MaterialTheme.shapes.small)
                            .padding(2.dp)
                    ) {
                        ImageLoader.PhotoItem(cluster.coverPhoto, Modifier.fillMaxSize()) {
                            selectedCluster = cluster
                        }
                        if (cluster.count > 1) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 8.dp, y = (-8).dp)
                                    .size(22.dp)
                                    .background(Color.Red, CircleShape)
                                    .border(1.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = cluster.count.toString(),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                selectedCluster?.let { selectedCluster ->
                    Surface(Modifier.align(Alignment.BottomCenter), color = MaterialTheme.colorScheme.background) {
                        LazyRow(
                            Modifier.height(100.dp).padding(vertical = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Spacer(Modifier.padding(8.dp))
                            }
                            items(selectedCluster.allPhotos, key = { it.id }, contentType = { "photo_thumbnail" }) {
                                ImageLoader.PhotoItem(it, Modifier.fillMaxHeight().aspectRatio(1f)) {
                                    backStack.add(Route.PhotoPage(it.id, selectedCluster.allPhotos))
                                }
                            }
                            item {
                                Spacer(Modifier.padding(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
