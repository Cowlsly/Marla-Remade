package com.vayunmathur.photos.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.util.FaceCropTransformation
import com.vayunmathur.photos.util.FaceRecognizer
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.PeopleUiState
import com.vayunmathur.photos.util.PersonCluster

/** Binds [GalleryViewModel] to the stateless [PeopleScreen]. */
@Composable
fun PeoplePage(
    backStack: NavBackStack<Route>,
    galleryViewModel: GalleryViewModel,
) {
    val context = LocalContext.current
    val people by galleryViewModel.people.collectAsState()
    val indexing by galleryViewModel.faceIndexing.collectAsState()
    val scanned by galleryViewModel.faceScannedCount.collectAsState()
    val target by galleryViewModel.faceTargetCount.collectAsState()
    val modelsAvailable = remember { FaceRecognizer.modelsAvailable(context) }

    PeopleScreen(
        backStack = backStack,
        state = PeopleUiState(
            people = people,
            modelsAvailable = modelsAvailable,
            indexing = indexing,
            faceScannedCount = scanned,
            faceTargetCount = target,
        ),
    )
}

/**
 * The people grid, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 * Navigation is the screen's only callback, so there is no actions interface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    backStack: NavBackStack<Route>,
    state: PeopleUiState,
    /**
     * How a cluster paints its representative face. Previews pass a placeholder: the real
     * one crops a MediaStore image, which Layoutlib cannot decode.
     */
    faceThumbnail: @Composable (PersonCluster, Modifier) -> Unit = { person, modifier -> FaceThumbnail(person, modifier) },
) {
    // Show progress while there are still photos left to scan (or the worker is
    // actively running); it disappears on its own once everything is scanned.
    val showProgress = state.faceTargetCount > 0 &&
        (state.indexing || state.faceScannedCount < state.faceTargetCount)

    // RAW SCAFFOLD EXCEPTION: main-nav page with only a bottom NavigationBar and no top
    // app bar. AppScaffold always renders a top app bar (which would break the layout) and
    // the body is a Column (progress + grid), not a LazyColumn.
    Scaffold(
        bottomBar = { NavigationBar(Route.People, backStack) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (!state.modelsAvailable) {
                Text(
                    text = stringResource(R.string.people_model_missing),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (showProgress) {
                        FaceIndexingProgress(done = state.faceScannedCount, total = state.faceTargetCount)
                    }
                    if (state.people.isNotEmpty()) {
                        PeopleGrid(
                            people = state.people,
                            modifier = Modifier.weight(1f),
                            faceThumbnail = faceThumbnail,
                        ) { person ->
                            backStack.add(Route.PhotoPage(person.coverPhoto.id, person.photos))
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(
                                    if (showProgress) R.string.people_scanning else R.string.people_empty
                                ),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FaceIndexingProgress(done: Int, total: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.face_indexing_progress, done, total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

@Composable
private fun PeopleGrid(
    people: List<PersonCluster>,
    modifier: Modifier = Modifier,
    faceThumbnail: @Composable (PersonCluster, Modifier) -> Unit,
    onClick: (PersonCluster) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize().padding(8.dp),
    ) {
        // Stable key = cluster id so Compose reuses items across re-emissions
        // instead of recreating (and re-loading) them during indexing.
        items(people, key = { it.id }) { person ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                faceThumbnail(
                    person,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .invisibleClickable { onClick(person) },
                )
                Text(
                    text = pluralStringResource(R.plurals.people_photo_count, person.photos.size, person.photos.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** The representative face of a cluster, cropped from its cover photo. Unnamed. */
@Composable
private fun FaceThumbnail(person: PersonCluster, modifier: Modifier) {
    val context = LocalContext.current
    // Remember the Coil request keyed only on the STABLE face identity (cluster
    // id + source file + face box). This keeps the model identity and cache keys
    // constant across re-emissions, so the same face is never re-decoded/re-fetched
    // (no flashing) even when the surrounding list re-emits during indexing.
    val cacheKey = "face_${person.id}_${person.coverPhoto.dateModified}"
    val request = remember(
        person.id,
        person.coverPhoto.uri,
        person.coverPhoto.dateModified,
        person.faceLeft, person.faceTop, person.faceRight, person.faceBottom,
    ) {
        ImageRequest.Builder(context)
            .data(person.coverPhoto.uri.toUri())
            .transformations(
                FaceCropTransformation(person.faceLeft, person.faceTop, person.faceRight, person.faceBottom)
            )
            .diskCacheKey(cacheKey)
            .memoryCacheKey(cacheKey)
            .crossfade(false)
            .size(256)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
