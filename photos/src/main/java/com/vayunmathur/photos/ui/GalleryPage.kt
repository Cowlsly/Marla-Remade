package com.vayunmathur.photos.ui

import com.vayunmathur.library.util.DateNameStyle
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SearchBar
import com.vayunmathur.library.ui.SearchBarDefaults
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.util.GalleryActions
import com.vayunmathur.photos.util.GalleryUiState
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.util.SearchAiState
import com.vayunmathur.photos.util.SecureFolderViewModel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.vayunmathur.library.util.localizedMonthNames
import kotlin.math.roundToInt
import kotlin.time.Instant
import com.vayunmathur.library.R as LibraryR

internal fun groupPhotosByMonth(
    photos: List<Photo>,
    resources: android.content.res.Resources,
): Map<String, List<Photo>> {
    val monthNames = localizedMonthNames(DateNameStyle.SHORT)
    return photos.groupBy {
        val date = Instant.fromEpochMilliseconds(it.date).toLocalDateTime(TimeZone.currentSystemDefault())
        LocalDate(date.year, date.month, 1)
    }.toSortedMap(compareByDescending { it }).mapKeys {
        resources.getString(R.string.month_year_format, monthNames[it.key.month.ordinal], it.key.year)
    }.mapValues { pair -> pair.value.sortedByDescending { it.date } }
}

/** Binds [GalleryViewModel] to the stateless [GalleryScreen]. */
@Composable
fun GalleryPage(
    backStack: NavBackStack<Route>,
    galleryViewModel: GalleryViewModel,
    secureFolderViewModel: SecureFolderViewModel,
) {
    val allPhotos by galleryViewModel.photos.collectAsState()
    val photos by remember { derivedStateOf { allPhotos.filter { !it.isTrashed } } }
    val context = LocalContext.current

    val selectedIds by galleryViewModel.selectedIds.collectAsState()
    val isRefreshing by galleryViewModel.isRefreshing.collectAsState()

    val searchQuery by galleryViewModel.searchQuery.collectAsState()
    val searchResults by galleryViewModel.searchResults.collectAsState()
    val searchAiState by galleryViewModel.searchAiState.collectAsState()
    val ocrCount by galleryViewModel.ocrCount.collectAsState()
    val ocrTargetCount by galleryViewModel.ocrTargetCount.collectAsState()
    val clipCount by galleryViewModel.clipCount.collectAsState()
    val clipTargetCount by galleryViewModel.clipTargetCount.collectAsState()

    LaunchedEffect(Unit) {
        galleryViewModel.runSync()
        galleryViewModel.enqueueSync()
    }

    val mediaResultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            galleryViewModel.clearSelection()
            galleryViewModel.runSync()
        }
    }

    // Helper to request MANAGE_MEDIA permission
    val manageMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // User returned from Settings - permission may or may not be granted
        // The next delete attempt will check again
    }
    
    fun requestManageMediaPermission() {
        if (!MediaStore.canManageMedia(context)) {
            val intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
                data = "package:${context.packageName}".toUri()
            }
            manageMediaLauncher.launch(intent)
        }
    }
    
    val onMoveToSecureClick: () -> Unit = onMoveToSecureClick@{
        val activity = context as FragmentActivity
        val selectedPhotos = photos.filter { it.id in selectedIds }
        
        secureFolderViewModel.unlock(
            activity,
            onSuccess = { _, _ ->
                secureFolderViewModel.moveToSecure(
                    photos = selectedPhotos,
                    sourceRepository = com.vayunmathur.photos.data.PhotosRepository.get(context.applicationContext),
                ) { urisToDelete ->
                    // Use MediaStore operations to delete files
                    // MANAGE_MEDIA permission is required to run the app (checked in PermissionsWrapper)
                    try {
                        val pendingIntent = MediaStore.createDeleteRequest(
                            context.contentResolver,
                            urisToDelete
                        )
                        // With MANAGE_MEDIA permission granted, this will delete without popup
                        mediaResultLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("GalleryPage", "MediaStore delete request failed", e)
                        // Fallback: clear selection and refresh anyway
                        galleryViewModel.clearSelection()
                        galleryViewModel.runSync()
                    }
                }
            },
            onFailure = {},
        )
    }

    val onDeleteClick: () -> Unit = {
        val uris = photos.filter { it.id in selectedIds }.map { it.uri.toUri() }
        val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, true)
        mediaResultLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    GalleryScreen(
        backStack = backStack,
        state = GalleryUiState(
            photos = photos,
            selectedIds = selectedIds,
            isRefreshing = isRefreshing,
            searchQuery = searchQuery,
            searchResults = searchResults,
            searchAiState = searchAiState,
            ocrCount = ocrCount,
            ocrTargetCount = ocrTargetCount,
            clipCount = clipCount,
            clipTargetCount = clipTargetCount,
        ),
        // The ViewModel is the actions implementation; the two MediaStore operations are
        // the exception, because they need the launchers created above.
        actions = object : GalleryActions by galleryViewModel {
            override fun moveSelectionToSecureFolder() = onMoveToSecureClick()
            override fun trashSelection() = onDeleteClick()
        },
    )
}

/**
 * The gallery grid, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    backStack: NavBackStack<Route>,
    state: GalleryUiState,
    actions: GalleryActions,
    /**
     * Seed for the screen's own UI-only state (whether the search bar is expanded). The app
     * always takes the default; a preview sets it to capture that view without driving the UI.
     */
    initialSearchActive: Boolean = false,
    /**
     * How a grid tile paints its photo. Previews pass a placeholder, because Layoutlib has
     * neither MediaStore nor the [ImageLoader] singleton MainActivity initialises.
     */
    thumbnail: @Composable (Photo, Modifier) -> Unit = { photo, modifier -> ImageLoader.PhotoItem(photo, modifier) },
) {
    var columnCount by LocalColumnCount.current
    var searchActive by remember { mutableStateOf(initialSearchActive) }
    val isSelectionMode = state.selectedIds.isNotEmpty()

    // Keyed on the incoming lists rather than derivedStateOf: `state` is a plain value, so
    // there is nothing for a derived state to observe.
    val resources = LocalResources.current
    val photosGroupedByMonth = remember(state.photos, resources) {
        groupPhotosByMonth(state.photos, resources)
    }
    val searchResultsGroupedByMonth = remember(state.searchResults, resources) {
        groupPhotosByMonth(state.searchResults, resources)
    }

    // RAW SCAFFOLD EXCEPTION: top bar is an expandable SearchBar (with in-bar search
    // results) swapped for a selection TopAppBar, and the body is a pinch-to-zoom photo
    // grid wrapped in PullToRefreshBox. Neither AppScaffold (title/actions only) nor
    // LazyListScaffold (LazyColumn body) can represent this without changing the UI.
    Scaffold(
        topBar = {
            Column {
                if (isSelectionMode) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.items_selected, state.selectedIds.size)) },
                        navigationIcon = {
                            IconButton(onClick = { actions.clearSelection() }) {
                                IconClose()
                            }
                        },
                        actions = {
                            IconButton(onClick = { actions.moveSelectionToSecureFolder() }) {
                                IconLock()
                            }
                            IconButton(onClick = { actions.trashSelection() }) {
                                IconDelete()
                            }
                        }
                    )
                } else {
                    SearchBar(
                        inputField = {
                            SearchBarDefaults.InputField(
                                query = state.searchQuery,
                                onQueryChange = { actions.setSearchQuery(it) },
                                onSearch = { searchActive = false },
                                expanded = searchActive,
                                onExpandedChange = { searchActive = it },
                                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                                leadingIcon = {
                                    IconSearch()
                                },
                                trailingIcon = {
                                    if (searchActive) {
                                        IconButton(onClick = {
                                            if (state.searchQuery.isNotEmpty()) {
                                                actions.setSearchQuery("")
                                            } else {
                                                searchActive = false
                                            }
                                        }) {
                                            IconClose()
                                        }
                                    }
                                },
                            )
                        },
                        expanded = searchActive,
                        onExpandedChange = { searchActive = it },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // Search bar expanded content
                        if (state.searchQuery.isNotEmpty()) {
                            // Semantic search runs on-device; if the bundled model failed to
                            // load, say so (OCR/filename results, if any, still show below).
                            val aiMessage = when (state.searchAiState) {
                                SearchAiState.UNAVAILABLE ->
                                    stringResource(R.string.semantic_search_unavailable)
                                SearchAiState.READY -> null
                            }
                            if (aiMessage != null) {
                                ListItem(
                                    content = { Text(aiMessage) },
                                    leadingContent = { IconSearch() },
                                )
                            }
                            // Show search results as a photo grid
                            LazyVerticalGrid(
                                GridCells.Fixed(3),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxSize().padding(4.dp)
                            ) {
                                items(state.searchResults, key = { it.id }, contentType = { "photo_thumbnail" }) { photo ->
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .invisibleClickable {
                                                searchActive = false
                                                backStack.add(Route.PhotoPage(photo.id, state.searchResults))
                                            }
                                    ) {
                                        thumbnail(photo, Modifier.fillMaxSize())
                                    }
                                }
                            }
                        } else {
                            // Show on-device indexing progress (text + visual).
                            if (state.ocrTargetCount > 0) {
                                val pct = state.ocrCount * 100 / state.ocrTargetCount
                                ListItem(
                                    content = { Text(stringResource(R.string.of_photos_processed, pct)) },
                                    supportingContent = { Text(stringResource(R.string.photos_indexed_for_text_search, state.ocrCount, state.ocrTargetCount)) },
                                    leadingContent = {
                                        CircularProgressIndicator(
                                            progress = { state.ocrCount.toFloat() / state.ocrTargetCount },
                                            modifier = Modifier.size(40.dp),
                                            strokeWidth = 4.dp
                                        )
                                    }
                                )
                            }
                            if (state.clipTargetCount > 0) {
                                val pct = state.clipCount * 100 / state.clipTargetCount
                                ListItem(
                                    content = { Text(stringResource(R.string.of_photos_processed, pct)) },
                                    supportingContent = { Text(stringResource(R.string.photos_indexed_for_visual_search, state.clipCount, state.clipTargetCount)) },
                                    leadingContent = {
                                        CircularProgressIndicator(
                                            progress = { state.clipCount.toFloat() / state.clipTargetCount },
                                            modifier = Modifier.size(40.dp),
                                            strokeWidth = 4.dp
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = { if (!isSelectionMode) NavigationBar(Route.Gallery, backStack) }
    ) { paddingValues ->
        com.vayunmathur.library.ui.PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { actions.runSync() },
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pinchToZoomColumns({ columnCount }, { columnCount = it })
            ) {
                LazyVerticalGrid(
                    GridCells.Fixed(columnCount.roundToInt().coerceIn(2, 8)),
                    Modifier,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val currentGroupedPhotos = if (searchActive && state.searchQuery.isNotEmpty()) searchResultsGroupedByMonth else photosGroupedByMonth
                    currentGroupedPhotos.forEach { (month, photosInMonth) ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                month,
                                Modifier.padding(top = 16.dp, bottom = 8.dp, start = 16.dp),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        items(photosInMonth, { it.id }, contentType = { "photo_thumbnail" }) { photo ->
                            val isSelected = photo.id in state.selectedIds
                            ImageLoader.SelectablePhotoItem(
                                photo = photo,
                                isSelected = isSelected,
                                isSelectionMode = isSelectionMode,
                                onToggleSelection = { actions.toggleSelection(photo.id) },
                                onClick = {
                                    if (isSelectionMode) {
                                        actions.toggleSelection(photo.id)
                                    } else {
                                        backStack.add(Route.PhotoPage(photo.id, null))
                                    }
                                },
                                thumbnail = thumbnail,
                            )
                        }
                    }
                }
            }
        }
    }
}

fun Modifier.pinchToZoomColumns(getColumnCount: () -> Float, setColumnCount: (Float) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        setColumnCount((getColumnCount() / zoom).coerceIn(2f, 8f))
                        event.changes.forEach { it.consume() }
                    }
                }
                if (event.changes.all { it.changedToUp() }) break
            }
        }
    }
