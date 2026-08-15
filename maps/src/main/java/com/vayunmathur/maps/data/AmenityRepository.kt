package com.vayunmathur.maps.data

import android.content.Context

/**
 * Singleton owner of the [AmenityDatabase]. Uses the existing custom
 * [buildAmenityDatabase] (external file + createFromFile, unencrypted) so
 * the builder stays identical; we just ensure it is called once and the
 * instance is shared process-wide instead of threaded through composables.
 */
class AmenityRepository private constructor(private val db: AmenityDatabase) {

    fun tagDao(): TagDao = db.tagDao()
    fun amenityDao(): AmenityDao = db.amenityDao()
    fun addressDao(): AddressDao = db.addressDao()

    // Convenience wrappers used by ViewModels
    suspend fun getTags(nodeId: Long): List<AmenityTag> = db.tagDao().getTags(nodeId)
    suspend fun getInBBox(query: String, latMin: Double, lonMin: Double, latMax: Double, lonMax: Double): List<AmenityEntity> =
        db.amenityDao().getInBBox(query, latMin, lonMin, latMax, lonMax)
    suspend fun searchAddresses(query: String, limit: Int = 50): List<AddressResult> =
        db.addressDao().search(query, limit)
    fun getDatabase(): AmenityDatabase = db

    companion object {
        @Volatile private var instance: AmenityRepository? = null

        fun get(context: Context): AmenityRepository =
            instance ?: synchronized(this) {
                instance ?: AmenityRepository(buildAmenityDatabase(context.applicationContext)).also { instance = it }
            }
    }
}
