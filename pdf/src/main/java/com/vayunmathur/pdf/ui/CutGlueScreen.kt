package com.vayunmathur.pdf.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.util.ComposePdfDocument
import com.vayunmathur.pdf.util.SafePdfPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.longPressDraggableHandle
import com.vayunmathur.library.ui.rememberReorderableLazyGridState
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.ui.res.stringResource

/**
 * "Cut and glue": compose a new PDF by appending whole PDFs or images, then
 * drag pages into the desired order. Starts empty.
 */
@Composable
fun CutGlueScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler { onBack() }
    val doc = remember { ComposePdfDocument.create() }
    DisposableEffect(doc) { onDispose { doc.close() } }

    // Stable per-page keys so drag-reorder animates; order mirrors native pages.
    val pageKeys = remember { mutableStateListOf<Long>() }
    var nextKey by remember { mutableIntStateOf(0) }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            } ?: return@launch
            val added = doc.appendPdf(bytes)
            repeat(added) { pageKeys.add(nextKey++.toLong()) }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val jpeg = withContext(Dispatchers.IO) { readAsJpegPage(context, uri) } ?: return@launch
            val ok = doc.appendImage(jpeg.bytes, jpeg.width, jpeg.height)
            if (ok > 0) pageKeys.add(nextKey++.toLong())
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { outUri ->
        if (outUri != null) scope.launch {
            val bytes = doc.save()
            if (bytes != null) withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(outUri)?.use { it.write(bytes) } }
            }
        }
    }

    CutGlueContent(
        pageKeys = pageKeys,
        renderPage = { index -> doc.renderPage(index) },
        onAppendImage = {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onAppendPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
        onSave = { saveLauncher.launch("composed.pdf") },
        onMove = { from, to ->
            val k = pageKeys.removeAt(from)
            pageKeys.add(to, k)
            scope.launch { doc.movePage(from, to) }
        },
        onDelete = { index ->
            pageKeys.removeAt(index)
            scope.launch { doc.removePage(index) }
        },
        onBack = onBack,
    )
}

/**
 * The page grid, with no handle on the native document: pages arrive as decoded
 * [SafePdfPage]s through [renderPage], and every mutation is reported back. That is what
 * lets a `@Preview` render it from hand-built pages — see `src/screenshotTest`, which is
 * where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CutGlueContent(
    pageKeys: List<Long>,
    renderPage: suspend (index: Int) -> SafePdfPage?,
    onAppendImage: () -> Unit = {},
    onAppendPdf: () -> Unit = {},
    onSave: () -> Unit = {},
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    onDelete: (index: Int) -> Unit = {},
    onBack: () -> Unit = {},
    /**
     * Pages already decoded, keyed as in [pageKeys]. The app starts empty and fills the
     * cache through [renderPage]; a preview seeds it instead, because [renderPage] would
     * have to run as an effect and a still preview never gets that far.
     */
    initialPages: Map<Long, SafePdfPage> = emptyMap(),
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Rendered pages cached by stable page key. Appends/reorders/removes then
    // reuse already-rendered pages instead of re-rendering every visible
    // thumbnail, which is what made the grid slow to load.
    val pageCache = remember { mutableStateMapOf<Long, SafePdfPage>().apply { putAll(initialPages) } }

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        if (from.index < pageKeys.size && to.index < pageKeys.size) onMove(from.index, to.index)
    }

    AppScaffold(
        title = stringResource(R.string.cut_glue),
        onNavigateBack = onBack,
        actions = {
            if (pageKeys.isNotEmpty()) {
                IconButton(onSave) { IconSave() }
            }
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menuOpen = true }) { IconAdd() }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        leadingIcon = { com.vayunmathur.library.ui.IconImage() },
                        text = { Text(stringResource(R.string.append_image)) },
                        onClick = { menuOpen = false; onAppendImage() },
                    )
                    DropdownMenuItem(
                        leadingIcon = { com.vayunmathur.library.ui.IconShapeRectOutline() },
                        text = { Text(stringResource(R.string.append_pdf)) },
                        onClick = { menuOpen = false; onAppendPdf() },
                    )
                }
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (pageKeys.isEmpty()) {
                Text(stringResource(R.string.tap_to_append_a_pdf_or_image),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                ) {
                    items(pageKeys, key = { it }) { key ->
                        val index = pageKeys.indexOf(key)
                        ReorderableItem(reorderState, key = key) { _ ->
                            ComposePageThumb(
                                renderPage = renderPage,
                                pageKey = key,
                                index = index,
                                cache = pageCache,
                                onDelete = {
                                    if (index in pageKeys.indices) {
                                        pageCache.remove(key)
                                        onDelete(index)
                                    }
                                },
                                modifier = Modifier.longPressDraggableHandle(reorderState, key = key, index = index),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposePageThumb(
    renderPage: suspend (index: Int) -> SafePdfPage?,
    pageKey: Long,
    index: Int,
    cache: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, SafePdfPage>,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Render each page once and cache it by its stable key. Reorders and appends
    // then reuse the cached render instead of re-rendering every visible thumb.
    val page by produceState<SafePdfPage?>(cache[pageKey], pageKey) {
        cache[pageKey]?.let { value = it; return@produceState }
        if (index >= 0) {
            val rendered = renderPage(index)
            if (rendered != null) cache[pageKey] = rendered
            value = rendered
        }
    }
    val current = page
    val ratio = if (current != null && current.height > 0f) current.width / current.height else 0.75f
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(modifier),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(6.dp))
                .background(if (current == null) MaterialTheme.colorScheme.surfaceVariant else Color.White),
        ) {
            if (current == null || current.width <= 0f) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Canvas(Modifier.fillMaxSize()) { drawSafePage(current) }
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.align(Alignment.TopEnd)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        ) { IconDelete(tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private class JpegPage(val bytes: ByteArray, val width: Int, val height: Int)

/** Decode [uri] and re-encode as JPEG for an image page; null on failure. */
private fun readAsJpegPage(context: android.content.Context, uri: Uri): JpegPage? = runCatching {
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it)
    } ?: return null
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
    JpegPage(out.toByteArray(), bmp.width, bmp.height)
}.getOrNull()
