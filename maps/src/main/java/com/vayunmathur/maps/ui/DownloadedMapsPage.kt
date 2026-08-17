package com.vayunmathur.maps.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.ContentScale
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.R
import com.vayunmathur.maps.util.MapsZonesViewModel
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.ZoneDownloadManager
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedMapsPage(backStack: NavBackStack<Route>, zonesViewModel: MapsZonesViewModel) {
    val context = LocalContext.current
    val downloadedMaps by zonesViewModel.downloadedZones.collectAsState()
    val downloadingZones by zonesViewModel.downloadingZones.collectAsState()
    val graphStatus by zonesViewModel.graphStatus.collectAsState()

    var showDownloadDialogForZone by remember { mutableStateOf<Int?>(null) }
    var showDeleteDialogForZone by remember { mutableStateOf<Int?>(null) }
    var showGraphDownloadDialog by remember { mutableStateOf(false) }
    var showGraphDeleteDialog by remember { mutableStateOf(false) }
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .build()
    }

    // When the single global routing graph finishes downloading, reload the
    // router so it mmaps the freshly downloaded nodes.bin/edges.bin/… .
    LaunchedEffect(graphStatus) {
        if (graphStatus == ZoneDownloadManager.GraphStatus.FINISHED) {
            OfflineRouter.reload(context)
        }
    }

    AppScaffold(
        title = stringResource(R.string.downloaded_maps),
        backStack = backStack,
    ) { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            // Single global routing graph (P16) — one download for the whole
            // world's roads/lanes/transit, separate from the per-zone tiles.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.routing_graph_title),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when (graphStatus) {
                            ZoneDownloadManager.GraphStatus.FINISHED ->
                                stringResource(R.string.routing_graph_downloaded)
                            ZoneDownloadManager.GraphStatus.DOWNLOADING ->
                                stringResource(R.string.routing_graph_downloading)
                            ZoneDownloadManager.GraphStatus.NOT_STARTED ->
                                stringResource(R.string.routing_graph_not_downloaded)
                        },
                        fontSize = 12.sp,
                    )
                }
                when (graphStatus) {
                    ZoneDownloadManager.GraphStatus.FINISHED ->
                        IconButton(onClick = { showGraphDeleteDialog = true }) {
                            IconDelete()
                        }
                    ZoneDownloadManager.GraphStatus.DOWNLOADING -> {
                        // Progress shown via the system download notification.
                    }
                    ZoneDownloadManager.GraphStatus.NOT_STARTED ->
                        Button(onClick = { showGraphDownloadDialog = true }) {
                            Text(stringResource(R.string.download))
                        }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
            val imgMinLat = -85.0
            val imgMaxLat = 85.0

            fun merc(lat: Double): Double {
                val phi = lat * PI / 180.0
                return ln(tan(PI / 4.0 + phi / 2.0))
            }

            val totalMercHeight = merc(imgMaxLat) - merc(imgMinLat)
            val worldAspectRatio = (2.0 * PI / totalMercHeight).toFloat()

            Box(
                Modifier
                    .fillMaxSize()
                    .aspectRatio(worldAspectRatio)
            ) {
                // World Map Background Image
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data("file:///android_asset/world_map.png")
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )

                // Grid
                Column(Modifier.fillMaxSize()) {
                    val rowDefinitions = listOf(
                        imgMaxLat to 67.5,
                        67.5 to 45.0,
                        45.0 to 22.5,
                        22.5 to 0.0,
                        0.0 to -22.5,
                        -22.5 to -45.0,
                        -45.0 to -67.5,
                        -67.5 to imgMinLat
                    )

                    rowDefinitions.forEachIndexed { index, (topLat, bottomLat) ->
                        val weight = (merc(topLat) - merc(bottomLat)) / totalMercHeight
                        Row(Modifier.weight(weight.toFloat())) {
                            for (col in 0..7) {
                                val rowIdx = 7 - index
                                val zoneId = getZoneId(rowIdx, col)

                                val progress = downloadingZones[zoneId]
                                val isDownloaded = zoneId in downloadedMaps

                                val status = when {
                                    progress != null -> ZoneDownloadManager.ZoneStatus.DOWNLOADING
                                    isDownloaded -> ZoneDownloadManager.ZoneStatus.FINISHED
                                    else -> ZoneDownloadManager.ZoneStatus.NOT_STARTED
                                }

                                ZoneCell(
                                    status = status,
                                    progress = progress ?: 0f,
                                    onDownloadRequest = { showDownloadDialogForZone = zoneId },
                                    onDeleteRequest = { showDeleteDialogForZone = zoneId },
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }

    showDownloadDialogForZone?.let { zoneId ->
        AlertDialog(
            onDismissRequest = { showDownloadDialogForZone = null },
            confirmButton = {
                Button({
                    zonesViewModel.startDownload(zoneId)
                    showDownloadDialogForZone = null
                }) {
                    Text(stringResource(R.string.download))
                }
            },
            title = { Text(stringResource(R.string.download_offline_map_title)) },
            text = { Text(stringResource(R.string.download_offline_map_text, zoneId)) },
            dismissButton = {
                TextButton({ showDownloadDialogForZone = null }) {
                    Text(stringResource(UiR.string.cancel))
                }
            }
        )
    }

    showDeleteDialogForZone?.let { zoneId ->
        AlertDialog(
            onDismissRequest = { showDeleteDialogForZone = null },
            confirmButton = {
                Button({
                    zonesViewModel.deleteZone(zoneId)
                    showDeleteDialogForZone = null
                }) {
                    Text(stringResource(UiR.string.delete))
                }
            },
            title = { Text(stringResource(R.string.delete_offline_map_title)) },
            text = { Text(stringResource(R.string.delete_offline_map_text, zoneId)) },
            dismissButton = {
                TextButton({ showDeleteDialogForZone = null }) {
                    Text(stringResource(UiR.string.cancel))
                }
            }
        )
    }

    if (showGraphDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showGraphDownloadDialog = false },
            confirmButton = {
                Button({
                    zonesViewModel.startGraphDownload()
                    showGraphDownloadDialog = false
                }) {
                    Text(stringResource(R.string.download))
                }
            },
            title = { Text(stringResource(R.string.download_routing_graph_title)) },
            text = { Text(stringResource(R.string.download_routing_graph_text)) },
            dismissButton = {
                TextButton({ showGraphDownloadDialog = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            }
        )
    }

    if (showGraphDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showGraphDeleteDialog = false },
            confirmButton = {
                Button({
                    zonesViewModel.deleteGraph()
                    showGraphDeleteDialog = false
                }) {
                    Text(stringResource(UiR.string.delete))
                }
            },
            title = { Text(stringResource(R.string.delete_routing_graph_title)) },
            text = { Text(stringResource(R.string.delete_routing_graph_text)) },
            dismissButton = {
                TextButton({ showGraphDeleteDialog = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoneCell(
    status: ZoneDownloadManager.ZoneStatus,
    progress: Float,
    onDownloadRequest: () -> Unit,
    onDeleteRequest: () -> Unit,
    modifier: Modifier
) {
    val backgroundColor = when (status) {
        ZoneDownloadManager.ZoneStatus.FINISHED -> Color.Green.copy(alpha = 0.4f)
        ZoneDownloadManager.ZoneStatus.DOWNLOADING -> Color.Yellow.copy(alpha = 0.4f)
        ZoneDownloadManager.ZoneStatus.NOT_STARTED -> Color.Red.copy(alpha = 0.4f)
    }

    Box(
        modifier = modifier
            .background(backgroundColor)
            .border(0.5.dp, Color.White.copy(alpha = 0.2f))
            .combinedClickable(
                onClick = {
                    if (status == ZoneDownloadManager.ZoneStatus.NOT_STARTED) {
                        onDownloadRequest()
                    }
                },
                onLongClick = {
                    if (status == ZoneDownloadManager.ZoneStatus.FINISHED || status == ZoneDownloadManager.ZoneStatus.DOWNLOADING) {
                        onDeleteRequest()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            ZoneDownloadManager.ZoneStatus.FINISHED -> {
                IconButton(onClick = onDeleteRequest) {
                    IconDelete(tint = Color.White)
                }
            }
            ZoneDownloadManager.ZoneStatus.DOWNLOADING -> {
                Text(
                    text = stringResource(R.string.download_progress, (progress * 100).toInt()),
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            ZoneDownloadManager.ZoneStatus.NOT_STARTED -> {
                // Empty, tapping triggers download
            }
        }
    }
}

private fun getZoneId(row: Int, col: Int): Int {
    var zoneId = 0
    for (i in 0 until 3) {
        val colBit = (col shr i) and 1
        val rowBit = (row shr i) and 1
        zoneId = zoneId or (colBit shl (2 * i))
        zoneId = zoneId or (rowBit shl (2 * i + 1))
    }
    return zoneId
}
