package com.vayunmathur.photos.util

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.net.toUri
import com.vayunmathur.library.biometric.unlockDatabaseWithBiometrics
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.VaultPhoto
import com.vayunmathur.photos.data.VaultPhotoDao
import com.vayunmathur.photos.data.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Secure Folder (encrypted vault) feature.
 *
 * Owns:
 *  - vault biometric unlock + lazy [VaultPhotoDao] creation via [VaultRepository]
 *  - the observable list of [VaultPhoto]s (DAO Flow, switched on unlock)
 *  - decrypted-thumbnail bitmap cache (LRU, bounded)
 *  - encrypt/move and decrypt/restore operations off the main thread
 *
 * Bitmaps are recycled in [onCleared] to release native memory promptly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecureFolderViewModel(application: Application) : AndroidViewModel(application) {

    private val _vaultPhotoDao = MutableStateFlow<VaultPhotoDao?>(null)
    val vaultPhotoDao: StateFlow<VaultPhotoDao?> = _vaultPhotoDao.asStateFlow()

    private val _vaultPassword = MutableStateFlow<String?>(null)
    val vaultPassword: StateFlow<String?> = _vaultPassword.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    val photos: StateFlow<List<VaultPhoto>> = _vaultPhotoDao
        .flatMapLatest { dao -> dao?.getAllFlow() ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val sfm: SecureFolderManager by lazy { SecureFolderManager(application) }

    /**
     * One observable holder per thumbnail path.
     *
     * A grid item observes only its own entry, so a decrypted thumbnail
     * recomposes that one cell. Publishing a new whole-map identity per decrypt
     * (which is what this replaced) invalidated every visible item instead, so
     * scrolling a vault cost O(visible items) recompositions per decrypt.
     */
    private val thumbStates = mutableMapOf<String, MutableState<Bitmap?>>()

    // Bounded LRU cache for decrypted thumbnails. Cap 64 to prevent unbounded
    // bitmap retention while scrolling large vaults.
    // NOTE: An earlier implementation recycled bitmaps synchronously in
    // removeEldestEntry while the UI still held references, causing
    // "Canvas: trying to use a recycled bitmap" when column count >=4
    // (more thumbnails visible -> cache overflow -> recycled while drawing).
    // Nothing is recycled on eviction: the UI's reference is dropped and the
    // bitmap is left to the collector. Recycling only happens in onCleared(),
    // after every state holder has been cleared.
    private val thumbCache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            if (size <= 64) return false
            thumbStates[eldest.key]?.value = null
            return true
        }
    }

    /**
     * Observable holder for one thumbnail, created on first request and kept for the
     * life of the ViewModel: an item has to keep observing the same instance to be
     * told when its thumbnail is re-decrypted after an eviction.
     */
    fun thumbnailState(thumbnailPath: String): State<Bitmap?> = synchronized(thumbCache) {
        thumbStates.getOrPut(thumbnailPath) {
            mutableStateOf(thumbCache[thumbnailPath]?.takeIf { !it.isRecycled })
        }
    }

    fun setVault(dao: VaultPhotoDao, password: String) {
        _vaultPassword.value = password
        _vaultPhotoDao.value = dao
    }

    fun unlock(
        activity: FragmentActivity,
        onSuccess: (VaultPhotoDao, String) -> Unit = { _, _ -> },
        onFailure: (message: String?) -> Unit = {},
    ) {
        val existingDao = _vaultPhotoDao.value
        if (existingDao != null) {
            onSuccess(existingDao, _vaultPassword.value!!)
            return
        }
        unlockDatabaseWithBiometrics(
            activity,
            onSuccess = { password ->
                // Fix activity-context leak: use applicationContext via VaultRepository
                val repo = VaultRepository.get(getApplication<Application>().applicationContext, password)
                val dao = repo.dao()
                setVault(dao, password)
                onSuccess(dao, password)
            },
            onFailure = onFailure,
        )
    }

    /**
     * Decrypt a single thumbnail and publish it into that path's state holder (see
     * [thumbnailState]). Cached results return immediately without re-decrypting.
     */
    fun requestThumbnail(thumbnailPath: String, password: String) {
        synchronized(thumbCache) {
            val cached = thumbCache[thumbnailPath]
            if (cached != null) {
                if (!cached.isRecycled) {
                    thumbStates[thumbnailPath]?.value = cached
                    return
                } else {
                    // Drop recycled entry if it somehow ended up recycled
                    thumbCache.remove(thumbnailPath)
                    thumbStates[thumbnailPath]?.value = null
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bmp = sfm.decryptThumbnail(thumbnailPath, password) ?: return@launch
                val published: Bitmap
                synchronized(thumbCache) {
                    val existing = thumbCache[thumbnailPath]
                    if (existing != null && !existing.isRecycled) {
                        // Another thread already inserted, recycle the duplicate we just decrypted
                        try { bmp.recycle() } catch (e: Exception) { Log.w(TAG, "Failed to recycle duplicate thumbnail", e) }
                        published = existing
                    } else {
                        if (existing != null) {
                            // Existing was recycled, remove it
                            thumbCache.remove(thumbnailPath)
                        }
                        thumbCache[thumbnailPath] = bmp
                        published = bmp
                    }
                    thumbStates[thumbnailPath]?.value = published
                }
            } catch (e: Exception) {
                Log.e(TAG, "decryptThumbnail failed for $thumbnailPath", e)
            }
        }
    }

    fun toggleSelection(id: Long) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    fun addSelection(id: Long) {
        _selectedIds.update { it + id }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    /**
     * Restore a list of vault photos back to the MediaStore and delete the
     * matching VaultPhoto rows. Errors per photo are swallowed (mirrors
     * the existing UI behaviour).
     */
    fun restorePhotos(photos: List<VaultPhoto>) {
        if (photos.isEmpty()) return
        val vaultDao = _vaultPhotoDao.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                photos.forEach { photo ->
                    val restored = sfm.decryptAndRestore(photo, _vaultPassword.value ?: return@forEach)
                    if (restored != null) {
                        vaultDao.delete(photo)
                    }
                }
                clearSelection()
            } catch (e: Exception) {
                Log.e(TAG, "restorePhotos failed", e)
            }
        }
    }

    /**
     * Encrypt and move [photos] into the vault. Returns the original MediaStore
     * URIs through [onSuccess] so the caller can issue the MediaStore delete
     * request (the only step that must run on the activity).
     * [sourceRepository] is the PhotosRepository for deleting from the main DB.
     */
    fun moveToSecure(
        photos: List<Photo>,
        sourceRepository: PhotosRepository,
        onSuccess: (List<android.net.Uri>) -> Unit,
    ) {
        if (photos.isEmpty()) return
        val vaultDao = _vaultPhotoDao.value ?: return
        viewModelScope.launch {
            val urisToDelete = withContext(Dispatchers.IO) {
                val collected = mutableListOf<android.net.Uri>()
                val password = _vaultPassword.value ?: return@withContext collected
                photos.forEach { photo ->
                    try {
                        val (path, thumbPath) = sfm.encryptAndMove(
                            photo.uri.toUri(),
                            photo.name,
                            password,
                        )
                        vaultDao.upsert(
                            VaultPhoto(
                                name = photo.name,
                                path = path,
                                thumbnailPath = thumbPath,
                                date = photo.date,
                                width = photo.width,
                                height = photo.height,
                                dateModified = photo.dateModified,
                                videoDuration = photo.videoData?.duration,
                            )
                        )
                        collected.add(photo.uri.toUri())
                        sourceRepository.delete(photo)
                    } catch (e: Exception) {
                        Log.e(TAG, "encryptAndMove failed for ${photo.uri}", e)
                    }
                }
                collected
            }
            if (urisToDelete.isNotEmpty()) {
                onSuccess(urisToDelete)
            }
        }
    }

    /**
     * Legacy overload taking a PhotoDao — delegates to repository overload.
     * Kept for incremental migration; callers should pass PhotosRepository.
     */
    fun moveToSecure(
        photos: List<Photo>,
        sourcePhotoDao: com.vayunmathur.photos.data.PhotoDao,
        onSuccess: (List<android.net.Uri>) -> Unit,
    ) {
        // Resolve repository from application context
        val repo = PhotosRepository.get(getApplication())
        moveToSecure(photos, repo, onSuccess)
    }

    override fun onCleared() {
        synchronized(thumbCache) {
            // Clear every holder first so the UI no longer references any bitmap,
            // then recycle. Order matters: this is the one place recycling happens.
            thumbStates.values.forEach { it.value = null }
            thumbStates.clear()
            thumbCache.values.forEach { bmp ->
                try { if (!bmp.isRecycled) bmp.recycle() } catch (e: Exception) { Log.w(TAG, "Failed to recycle thumbnail on clear", e) }
            }
            thumbCache.clear()
        }
    }

    companion object {
        private const val TAG = "SecureFolderViewModel"
    }
}

@Suppress("FunctionName")
fun SecureFolderViewModelFactory(application: Application): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { SecureFolderViewModel(application) }
    }
