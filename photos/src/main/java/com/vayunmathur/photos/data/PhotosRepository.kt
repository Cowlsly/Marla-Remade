package com.vayunmathur.photos.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for PhotoDatabase.
 * Owns the one [PhotoDatabase] instance via [RoomRepository] and is the only place
 * DAOs are touched. All consumers obtain data through here.
 */
class PhotosRepository private constructor(context: Context) :
    RoomRepository<PhotoDatabase>(context, PhotoDatabase::class) {

    private val photoDao: PhotoDao get() = db.photoDao()
    private val faceDao: FaceDao get() = db.faceDao()

    // ------------------------------------------------------------------
    // PhotoDao read Flows
    // ------------------------------------------------------------------
    fun getAllFlow(): Flow<List<Photo>> = photoDao.getAllFlow()
    fun getByIdFlow(id: Long): Flow<Photo?> = photoDao.getByIdFlow(id)
    fun getOCRCountFlow(): Flow<Int> = photoDao.getOCRCountFlow()
    fun getClipCountFlow(): Flow<Int> = photoDao.getClipCountFlow()
    fun getFaceScannedCountFlow(): Flow<Int> = photoDao.getFaceScannedCountFlow()

    /** Shared denominator for the OCR, CLIP and face progress bars. */
    fun getIndexTargetCountFlow(): Flow<Int> = photoDao.getIndexTargetCountFlow()

    // FaceDao read Flows
    fun personsFlow(): Flow<List<Person>> = faceDao.personsFlow()
    fun faceGeometryFlow(): Flow<List<FaceGeometry>> = faceDao.faceGeometryFlow()

    // ------------------------------------------------------------------
    // PhotoDao suspend wrappers
    // ------------------------------------------------------------------
    suspend fun getAll(): List<Photo> = photoDao.getAll()
    suspend fun getByUri(uri: String): List<Photo> = photoDao.getByUri(uri)
    suspend fun upsertAll(photos: List<Photo>) = photoDao.upsertAll(photos)
    suspend fun delete(value: Photo): Int = photoDao.delete(value)
    suspend fun deleteByIds(ids: List<Long>) = photoDao.deleteByIds(ids)
    suspend fun setTrashed(id: Long) = photoDao.setTrashed(id)
    suspend fun searchPhotos(query: String): List<Photo> = photoDao.searchPhotos(query)
    suspend fun getUnscannedForOCR(): List<PhotoScanTarget> = photoDao.getUnscannedForOCR()
    suspend fun getUnscannedForFaces(): List<PhotoScanTarget> = photoDao.getUnscannedForFaces()
    suspend fun getUnscannedForClip(): List<PhotoScanTarget> = photoDao.getUnscannedForClip()
    suspend fun getUnscannedForExif(): List<PhotoExifTarget> = photoDao.getUnscannedForExif()
    suspend fun setOcrScanned(ids: List<Long>) = photoDao.setOcrScanned(ids)
    suspend fun setOcrResults(results: List<OcrResult>) = photoDao.setOcrResults(results)
    suspend fun setClipResults(results: List<ClipResult>) = photoDao.setClipResults(results)
    suspend fun setExifResults(results: List<ExifResult>) = photoDao.setExifResults(results)
    suspend fun getClipEmbeddings(): List<PhotoEmbedding> = photoDao.getClipEmbeddings()
    suspend fun resetClipScanned() = photoDao.resetClipScanned()
    suspend fun resetFaceScanned() = photoDao.resetFaceScanned()
    suspend fun countMissingMimeType(): Int = photoDao.countMissingMimeType()
    suspend fun getOcrBoxes(id: Long): String? = photoDao.getOcrBoxes(id)
    suspend fun getStillPhotoUris(): List<String> = photoDao.getStillPhotoUris()
    suspend fun setOcrBoxes(id: Long, json: String?) = photoDao.setOcrBoxes(id, json)

    // ------------------------------------------------------------------
    // FaceDao suspend wrappers
    // ------------------------------------------------------------------
    suspend fun insertPerson(person: Person): Long = faceDao.insertPerson(person)
    suspend fun updateClusterCentroid(id: Long, centroid: ByteArray, faceCount: Int) =
        faceDao.updateClusterCentroid(id, centroid, faceCount)
    suspend fun mergeClusterInto(id: Long, centroid: ByteArray, faceCount: Int, fallbackName: String?) =
        faceDao.mergeClusterInto(id, centroid, faceCount, fallbackName)
    suspend fun getPersons(): List<Person> = faceDao.getPersons()
    suspend fun clearPersons() = faceDao.clearPersons()
    suspend fun deletePerson(id: Long) = faceDao.deletePerson(id)
    suspend fun setPersonName(id: Long, name: String?) = faceDao.setPersonName(id, name)
    suspend fun clearPhotoFaces() = faceDao.clearPhotoFaces()
    suspend fun reassignCluster(oldId: Long, newId: Long) = faceDao.reassignCluster(oldId, newId)
    suspend fun photoIdsForCluster(clusterId: Long): List<Long> = faceDao.photoIdsForCluster(clusterId)

    /**
     * Commit one batch of face scanning atomically: replace the face rows for
     * [photoIds] and mark those photos scanned in the same transaction.
     *
     * All three statements have to land together. If the rows were written but the
     * flag was not (a worker killed mid-batch, which WorkManager does routinely),
     * the next run would re-scan those photos and insert a second set of rows for
     * them. Delete-then-insert also makes a re-scan idempotent on its own.
     */
    suspend fun commitFaceScan(photoIds: List<Long>, faces: List<PhotoFace>) =
        db.withTransaction {
            faceDao.deletePhotoFacesByPhotoIds(photoIds)
            if (faces.isNotEmpty()) faceDao.insertPhotoFaces(faces)
            photoDao.setFaceScanned(photoIds)
        }

    companion object {
        @Volatile
        private var instance: PhotosRepository? = null

        fun get(context: Context): PhotosRepository =
            instance ?: synchronized(this) {
                instance ?: PhotosRepository(context).also { instance = it }
            }
    }
}
