package com.vayunmathur.photos.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
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
    fun getOCRTargetCountFlow(): Flow<Int> = photoDao.getOCRTargetCountFlow()
    fun getClipCountFlow(): Flow<Int> = photoDao.getClipCountFlow()
    fun getClipTargetCountFlow(): Flow<Int> = photoDao.getClipTargetCountFlow()
    fun getFaceTargetCountFlow(): Flow<Int> = photoDao.getFaceTargetCountFlow()
    fun getFaceScannedCountFlow(): Flow<Int> = photoDao.getFaceScannedCountFlow()

    // FaceDao read Flows
    fun personsFlow(): Flow<List<Person>> = faceDao.personsFlow()
    fun allFacesFlow(): Flow<List<PhotoFace>> = faceDao.allFacesFlow()

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
    suspend fun getUnscannedForOCR(): List<Photo> = photoDao.getUnscannedForOCR()
    suspend fun getUnscannedForFaces(): List<Photo> = photoDao.getUnscannedForFaces()
    suspend fun getUnscannedForClip(): List<Photo> = photoDao.getUnscannedForClip()
    suspend fun getClipEmbeddings(): List<PhotoEmbedding> = photoDao.getClipEmbeddings()
    suspend fun resetClipScanned() = photoDao.resetClipScanned()
    suspend fun resetFaceScanned() = photoDao.resetFaceScanned()
    suspend fun countMissingMimeType(): Int = photoDao.countMissingMimeType()
    suspend fun getOcrBoxes(id: Long): String? = photoDao.getOcrBoxes(id)
    suspend fun setOcrBoxes(id: Long, json: String?) = photoDao.setOcrBoxes(id, json)

    // ------------------------------------------------------------------
    // FaceDao suspend wrappers
    // ------------------------------------------------------------------
    suspend fun insertPerson(person: Person): Long = faceDao.insertPerson(person)
    suspend fun updatePerson(person: Person) = faceDao.updatePerson(person)
    suspend fun getPersons(): List<Person> = faceDao.getPersons()
    suspend fun clearPersons() = faceDao.clearPersons()
    suspend fun deletePerson(id: Long) = faceDao.deletePerson(id)
    suspend fun insertPhotoFaces(faces: List<PhotoFace>) = faceDao.insertPhotoFaces(faces)
    suspend fun deletePhotoFacesByPhotoIds(photoIds: List<Long>) = faceDao.deletePhotoFacesByPhotoIds(photoIds)
    suspend fun clearPhotoFaces() = faceDao.clearPhotoFaces()
    suspend fun reassignCluster(oldId: Long, newId: Long) = faceDao.reassignCluster(oldId, newId)
    suspend fun photoIdsForCluster(clusterId: Long): List<Long> = faceDao.photoIdsForCluster(clusterId)

    companion object {
        @Volatile
        private var instance: PhotosRepository? = null

        fun get(context: Context): PhotosRepository =
            instance ?: synchronized(this) {
                instance ?: PhotosRepository(context).also { instance = it }
            }
    }
}
