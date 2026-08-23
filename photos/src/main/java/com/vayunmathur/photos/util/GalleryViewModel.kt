package com.vayunmathur.photos.util

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotosRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.work.WorkInfo
import androidx.work.WorkManager

/**
 * ViewModel for the photos gallery screen.
 *
 * Owns:
 *  - the observable list of [Photo]s (backed by the DAO Flow)
 *  - OCR search query and asynchronously fetched search results
 *  - multi-select state (set of photo ids)
 *  - OCR feature-enabled flag (DataStore) and OCR progress counters (Flow)
 *  - sync worker entry-point side effects
 */
@OptIn(FlowPreview::class)
class GalleryViewModel(
    application: Application,
    private val repository: PhotosRepository,
) : AndroidViewModel(application), GalleryActions {

    // Kept for external callers that still reference photoDao/faceDao as PhotosRepository
    val photoDao get() = repository
    val faceDao get() = repository

    val photos: StateFlow<List<Photo>> = repository.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Photo>>(emptyList())
    val searchResults: StateFlow<List<Photo>> = _searchResults.asStateFlow()

    /**
     * Availability of on-device semantic search, updated on each search so the UI can explain
     * why AI results are missing when the bundled model fails to load. OCR/filename search is
     * unaffected.
     */
    private val _searchAiState = MutableStateFlow(SearchAiState.READY)
    val searchAiState: StateFlow<SearchAiState> = _searchAiState.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /** True while a media sync is enqueued or running (drives pull-to-refresh). */
    val isRefreshing: StateFlow<Boolean> = WorkManager.getInstance(application)
        .getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val ocrCount: StateFlow<Int> = photoDao.getOCRCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val ocrTargetCount: StateFlow<Int> = photoDao.getOCRTargetCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Photos already embedded with TinyCLIP for semantic search (progress numerator). */
    val clipCount: StateFlow<Int> = photoDao.getClipCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Photos that count toward semantic indexing (progress denominator). */
    val clipTargetCount: StateFlow<Int> = photoDao.getClipTargetCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** True while the face-grouping worker is actively indexing. */
    val faceIndexing: StateFlow<Boolean> = WorkManager.getInstance(application)
        .getWorkInfosForUniqueWorkFlow(FaceWorker.WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Photos already scanned for faces (progress numerator). */
    val faceScannedCount: StateFlow<Int> = photoDao.getFaceScannedCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Photos that need face scanning in total (progress denominator). */
    val faceTargetCount: StateFlow<Int> = photoDao.getFaceTargetCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Photos grouped by person-cluster, for the People view.
     *
     * A cluster's representative is derived here rather than stored: whichever of
     * its faces covers the most *source* pixels, which is the one that survives
     * being cropped into a thumbnail. Area is
     * `(right-left) * (bottom-top) * photo.width * photo.height`, and those two
     * fractions multiply against both dimensions, so the ranking is unaffected by
     * MediaStore's width/height being transposed for EXIF-rotated JPEGs.
     *
     * Deriving it also means the cover photo and the box always come from the
     * same face, so a trashed photo can no longer leave a box cropping an
     * unrelated image.
     */
    val people: StateFlow<List<PersonCluster>> =
        combine(photoDao.getAllFlow(), faceDao.personsFlow(), faceDao.allFacesFlow()) { allPhotos, persons, faces ->
            val byId = allPhotos.filter { !it.isTrashed }.associateBy { it.id }
            val facesByCluster = faces.groupBy { it.clusterId }
            persons.mapNotNull { person ->
                val clusterFaces = facesByCluster[person.id].orEmpty()
                    .mapNotNull { face -> byId[face.photoId]?.let { face to it } }
                if (clusterFaces.isEmpty()) return@mapNotNull null
                val (bestFace, bestPhoto) = clusterFaces.maxBy { (face, photo) ->
                    (face.right - face.left).toDouble() * (face.bottom - face.top) *
                        photo.width * photo.height
                }
                PersonCluster(
                    id = person.id,
                    name = person.name,
                    coverPhoto = bestPhoto,
                    faceLeft = bestFace.left,
                    faceTop = bestFace.top,
                    faceRight = bestFace.right,
                    faceBottom = bestFace.bottom,
                    photos = clusterFaces.map { it.second }
                        .distinctBy { it.id }
                        .sortedByDescending { it.date },
                )
            }.sortedByDescending { it.photos.size }
        }
            // Per-photo writes during indexing re-emit the source flows constantly;
            // drop emissions where the projected cluster list is unchanged (value
            // equality of the PersonCluster data class) so the grid doesn't churn.
            // The representative can legitimately change mid-scan, so real changes
            // still get through.
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Number of detected faces (i.e. people) per photo, for the photo detail overlay. */
    val faceCountByPhoto: StateFlow<Map<Long, Int>> =
        faceDao.allFacesFlow().map { faces ->
            faces.groupBy { it.photoId }.mapValues { (_, list) -> list.size }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Face geometry per photo, for the viewer's face overlay. Combined with the
     * clusters because the label text lives on [com.vayunmathur.photos.data.Person],
     * not on the face row — which is also why [faceCountByPhoto] can't be reused.
     */
    val faceBoxesByPhoto: StateFlow<Map<Long, PhotoFaceBoxes>> =
        combine(faceDao.allFacesFlow(), faceDao.personsFlow()) { faces, persons ->
            val nameByCluster = persons.associate { it.id to it.name }
            faces.groupBy { it.photoId }.mapValues { (_, rows) ->
                PhotoFaceBoxes(
                    // Duplicated across a photo's rows, so any one of them serves.
                    srcWidth = rows.first().srcWidth,
                    srcHeight = rows.first().srcHeight,
                    faces = rows.map { row ->
                        FaceBox(
                            clusterId = row.clusterId,
                            name = nameByCluster[row.clusterId],
                            left = row.left,
                            top = row.top,
                            right = row.right,
                            bottom = row.bottom,
                        )
                    },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        // Debounced search: re-query whenever the query string changes.
        viewModelScope.launch {
            _searchQuery
                .debounce(150)
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _searchResults.value = emptyList()
                        return@collectLatest
                    }
                    val results = withContext(Dispatchers.IO) {
                        combinedSearch(query)
                    }
                    Log.d(TAG, "Search '$query' returned ${results.size} photos")
                    _searchResults.value = results
                }
        }
    }

    /**
     * Combine the two on-device search signals, then order the result set
     * chronologically for the search menu — the query acts as a *filter*, not a
     * ranking:
     *  - OCR/name search: the existing case-insensitive LIKE over recognised text
     *    and file name.
     *  - Semantic search: embed the query on-device with TinyCLIP and
     *    cosine-compare it against stored image embeddings (see [ClipEmbedder]);
     *    keep matches at/above [SEMANTIC_THRESHOLD], capped to the top
     *    [MAX_SEMANTIC_RESULTS].
     *
     * The semantic cosine similarity, the [SEMANTIC_THRESHOLD]/[MAX_SEMANTIC_RESULTS]
     * cap and the [OCR_MATCH_BOOST] still decide **membership** (which photos are
     * relevant enough to include). Once that set is chosen, matches are
     * de-duplicated by photo id and displayed **most-recent-first**, like the
     * main grid — so the search bar reads as a chronological filter rather than a
     * relevance-ranked list.
     */
    private suspend fun combinedSearch(query: String): List<Photo> {
        // (a) OCR + filename LIKE search (existing behaviour).
        val ocrHits = try {
            photoDao.searchPhotos(query)
        } catch (e: Exception) {
            Log.e(TAG, "searchPhotos failed", e)
            emptyList()
        }
        val ocrIds = ocrHits.map { it.id }.toSet()

        // (b) Semantic search: embed the query on-device with TinyCLIP, then cosine vs stored
        // image embeddings. Inert (empty) if the model can't load; the AI-state flow is updated
        // so the UI can explain why.
        val semanticById: Map<Long, Float> = try {
            val textEmb = ClipEmbedder.textEmbedding(getApplication(), query)
            _searchAiState.value = SearchAiState.READY
            photoDao.getClipEmbeddings()
                .asSequence()
                .map { it.id to ClipEmbedder.cosine(textEmb, ClipEmbedder.bytesToFloats(it.clipEmbedding)) }
                .filter { it.second >= SEMANTIC_THRESHOLD }
                .sortedByDescending { it.second }
                .take(MAX_SEMANTIC_RESULTS)
                .toMap()
        } catch (e: Exception) {
            Log.e(TAG, "semantic search failed", e)
            _searchAiState.value = SearchAiState.UNAVAILABLE
            emptyMap()
        }

        // (c) Merge + dedupe by id, ranked by combined score. Include ocrHits in
        // the lookup so literal matches are never lost if the photos cache is cold.
        val photoById = (photos.value + ocrHits).associateBy { it.id }
        val ids = LinkedHashSet<Long>().apply {
            addAll(ocrIds)
            addAll(semanticById.keys)
        }
        return ids.mapNotNull { id ->
            val photo = photoById[id] ?: return@mapNotNull null
            if (photo.isTrashed) return@mapNotNull null
            photo
        }.sortedByDescending { it.date }
    }

    override fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    override fun toggleSelection(id: Long) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    override fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun deletePhoto(photo: Photo) {
        viewModelScope.launch(Dispatchers.IO) {
            photoDao.delete(photo)
        }
    }

    /**
     * Move a photo to trash in the DB immediately (so the viewer/grid drop it
     * without waiting for the MediaStore resync). The file itself is trashed via
     * the MediaStore request in the UI; [runSync] later reconciles the rest.
     */
    fun trashPhotoLocally(photo: Photo) {
        viewModelScope.launch(Dispatchers.IO) {
            photoDao.setTrashed(photo.id)
        }
    }

    override fun runSync() {
        SyncWorker.runOnce(getApplication())
    }

    /**
     * Index the single media [uri] into the gallery DB (so a freshly-captured
     * item handed to us via ACTION_VIEW exists before we open it), then return
     * its row id on the main thread. Also kicks a full background sync so the
     * rest of the library is fresh for swiping. Returns null if the item
     * couldn't be resolved.
     */
    fun resolveAndIndex(uri: Uri, onResolved: (Long?) -> Unit) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching { syncPhotos(getApplication(), repository, listOf(uri)) }
                repository.getByUri(uri.toString()).firstOrNull()?.id
            }
            runSync()
            onResolved(id)
        }
    }

    fun enqueueSync() {
        SyncWorker.enqueue(getApplication())
    }

    /** Name a person-cluster, or clear its name with null. */
    fun setPersonName(id: Long, name: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setPersonName(id, name)
        }
    }

    companion object {
        private const val TAG = "GalleryViewModel"

        /**
         * Minimum cosine similarity for a photo to count as a semantic match.
         * Single most-important tunable knob. SigLIP2 is sigmoid-trained and
         * 768-d, so its cosine distribution differs from the previous 512-d
         * embedding space — **retune this against real photos** after the move to
         * TinyCLIP. Higher = stricter/fewer, lower = looser/more; results
         * are ranked and capped so the best matches still surface first.
         */
        private const val SEMANTIC_THRESHOLD = 0.15f

        /** Cap on semantic matches merged into results (keeps the grid relevant). */
        private const val MAX_SEMANTIC_RESULTS = 100
    }
}

@Suppress("FunctionName")
fun GalleryViewModelFactory(
    application: Application,
    repository: PhotosRepository,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { GalleryViewModel(application, repository) }
}

/** Legacy overload kept so call sites updated incrementally compile. */
@Suppress("FunctionName")
fun GalleryViewModelFactory(
    application: Application,
    photoDao: com.vayunmathur.photos.data.PhotoDao,
    faceDao: com.vayunmathur.photos.data.FaceDao,
): ViewModelProvider.Factory {
    val repo = PhotosRepository.get(application)
    return GalleryViewModelFactory(application, repo)
}

/** A person-cluster and the library photos they appear in. */
data class PersonCluster(
    val id: Long,
    /** The name the user picked from their contacts, or null while unnamed. */
    val name: String?,
    val coverPhoto: Photo,
    val faceLeft: Float,
    val faceTop: Float,
    val faceRight: Float,
    val faceBottom: Float,
    val photos: List<Photo>,
)

/**
 * The faces detected in one photo, plus the dimensions their boxes are
 * normalised against — the display-orientation dimensions of the detection
 * bitmap, which is what the overlay needs to rebuild the letterbox rect.
 */
data class PhotoFaceBoxes(
    val srcWidth: Int,
    val srcHeight: Int,
    val faces: List<FaceBox>,
)

/** One face box (normalised 0..1) and the cluster it was grouped into. */
data class FaceBox(
    val clusterId: Long,
    val name: String?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Availability of on-device semantic search, for the search UI. */
enum class SearchAiState {
    /** The bundled TinyCLIP model loaded and is serving embeddings. */
    READY,

    /** The bundled model could not be loaded, so only OCR/filename search works. */
    UNAVAILABLE,
}
