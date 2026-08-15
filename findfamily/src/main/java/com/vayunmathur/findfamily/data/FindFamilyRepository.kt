package com.vayunmathur.findfamily.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for all FindFamily persisted data.
 *
 * Owns the one [FFDatabase] instance (via [RoomRepository]) and is the only place
 * the four DAOs are touched. The app (ViewModel), the background
 * [com.vayunmathur.findfamily.util.LocationTrackingService], networking, the
 * assistant intents, `LocationServiceController`, and the tracker all read/write
 * through here — so the UI and the service share one live, invalidation-backed
 * view of the data.
 *
 * Read access is exposed as cold [Flow]s (consumers apply `stateIn`/`flatMapLatest`
 * as before). Writes are `suspend` wrappers over the existing atomic DAO queries,
 * so no caller ever holds a DAO directly.
 */
class FindFamilyRepository private constructor(context: Context) :
    RoomRepository<FFDatabase>(context, FFDatabase::class) {

    private val userDao: UserDao get() = db.userDao()
    private val waypointDao: WaypointDao get() = db.waypointDao()
    private val locationValueDao: LocationValueDao get() = db.locationValueDao()
    private val temporaryLinkDao: TemporaryLinkDao get() = db.temporaryLinkDao()

    // ------------------------------------------------------------------
    // Read flows (cold)
    // ------------------------------------------------------------------

    val users: Flow<List<User>> get() = userDao.getAllFlow()
    val waypoints: Flow<List<Waypoint>> get() = waypointDao.getAllFlow()
    val temporaryLinks: Flow<List<TemporaryLink>> get() = temporaryLinkDao.getAllFlow()

    /** Latest [LocationValue] per user id (the "present" position of everyone). */
    val latestLocationByUser: Flow<Map<Long, LocationValue>>
        get() = locationValueDao.getLatest().map { list -> list.associateBy { it.userid } }

    /** Full location history for one user (the history scrubber). */
    fun locationHistory(userId: Long): Flow<List<LocationValue>> =
        locationValueDao.getByUseridFlow(userId)

    // ------------------------------------------------------------------
    // User reads / writes
    // ------------------------------------------------------------------

    suspend fun getUser(id: Long): User? = userDao.getById(id)
    suspend fun getAllUsers(): List<User> = userDao.getAll()
    suspend fun upsertUser(user: User): Long = userDao.upsert(user)
    suspend fun insertUsersIgnore(users: List<User>) = userDao.insertAllIgnore(users)
    suspend fun deleteUser(user: User): Int = userDao.delete(user)

    suspend fun updateLocationMeta(
        id: Long,
        locationName: String,
        lastWaypointId: Long?,
        lastLocationChangeTime: Long,
    ) = userDao.updateLocationMeta(id, locationName, lastWaypointId, lastLocationChangeTime)

    suspend fun setPlatform(id: Long, platform: String) = userDao.setPlatform(id, platform)
    suspend fun setPqcEncryptionKey(id: Long, pqcEncryptionKey: String) =
        userDao.setPqcEncryptionKey(id, pqcEncryptionKey)

    suspend fun setSharingAutoToggleAt(id: Long, atEpochSeconds: Long?) =
        userDao.setSharingAutoToggleAt(id, atEpochSeconds)

    suspend fun setSharingAutoToggleWaypointId(id: Long, waypointId: Long?) =
        userDao.setSharingAutoToggleWaypointId(id, waypointId)

    suspend fun setSendingEnabledAndClearToggle(id: Long, enabled: Boolean) =
        userDao.setSendingEnabledAndClearToggle(id, enabled)

    suspend fun applyDueAutoToggles(nowEpochSeconds: Long): Int =
        userDao.applyDueAutoToggles(nowEpochSeconds)

    suspend fun applyDueArrivalToggles(insideWaypointIds: List<Long>): Int =
        userDao.applyDueArrivalToggles(insideWaypointIds)

    suspend fun updateContactNamePhoto(id: Long, name: String, photo: String?) =
        userDao.updateContactNamePhoto(id, name, photo)

    // ------------------------------------------------------------------
    // Waypoint reads / writes
    // ------------------------------------------------------------------

    suspend fun getAllWaypoints(): List<Waypoint> = waypointDao.getAll()
    suspend fun getWaypoint(id: Long): Waypoint = waypointDao.get(id)
    suspend fun upsertWaypoint(waypoint: Waypoint): Long = waypointDao.upsert(waypoint)
    suspend fun deleteWaypoint(waypoint: Waypoint): Int = waypointDao.delete(waypoint)

    // ------------------------------------------------------------------
    // LocationValue reads / writes
    // ------------------------------------------------------------------

    suspend fun upsertLocation(value: LocationValue): Long = locationValueDao.upsert(value)
    suspend fun upsertLocations(values: List<LocationValue>) = locationValueDao.upsertAll(values)

    /** One-shot snapshot of the latest location per user (non-Flow). */
    suspend fun latestLocationsOnce(): List<LocationValue> = locationValueDao.getLatest().first()

    suspend fun deleteLocationsOlderThan(cutoffEpochSeconds: Long) =
        locationValueDao.deleteOlderThan(cutoffEpochSeconds)

    // ------------------------------------------------------------------
    // TemporaryLink reads / writes
    // ------------------------------------------------------------------

    suspend fun getAllTemporaryLinks(): List<TemporaryLink> = temporaryLinkDao.getAll()
    suspend fun upsertTemporaryLink(link: TemporaryLink): Long = temporaryLinkDao.upsert(link)
    suspend fun deleteTemporaryLink(link: TemporaryLink): Int = temporaryLinkDao.delete(link)

    companion object {
        @Volatile
        private var instance: FindFamilyRepository? = null

        /** The process-wide singleton repository. */
        fun get(context: Context): FindFamilyRepository =
            instance ?: synchronized(this) {
                instance ?: FindFamilyRepository(context).also { instance = it }
            }
    }
}
