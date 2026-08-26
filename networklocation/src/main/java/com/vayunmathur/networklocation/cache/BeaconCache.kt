package com.vayunmathur.networklocation.cache

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vayunmathur.networklocation.BeaconFix
import com.vayunmathur.networklocation.BeaconId

/** Stable string key for a beacon, used as the cache primary key. */
fun BeaconId.key(): String = when (this) {
    is BeaconId.Wifi -> "wifi:${bssid.lowercase()}"
    is BeaconId.Cell -> "cell:$mcc:$mnc:$cellId:$tacOrLac"
}

@Entity(tableName = "beacon")
data class CachedBeaconEntity(
    @PrimaryKey val key: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val updatedAt: Long,
)

@Dao
interface BeaconDao {
    @Query("SELECT * FROM beacon WHERE key IN (:keys)")
    suspend fun byKeys(keys: List<String>): List<CachedBeaconEntity>

    @Upsert
    suspend fun upsertAll(rows: List<CachedBeaconEntity>)

    @Query("DELETE FROM beacon WHERE updatedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Database(entities = [CachedBeaconEntity::class], version = 1, exportSchema = false)
abstract class BeaconDatabase : RoomDatabase() {
    abstract fun beaconDao(): BeaconDao
}

/**
 * Two-tier beacon-location cache: an in-memory [TimedLruCache] in front of a Room
 * table. Beacon coordinates are effectively static, so caching them locally is the
 * whole point — it means a given AP/tower is asked of Apple's proxy once, then
 * served offline forever (until the TTL sweep). The cache holds only public beacon
 * coordinates, so it is stored unencrypted.
 */
class BeaconCache(
    context: Context,
    private val memory: TimedLruCache<String, BeaconFix> =
        TimedLruCache(maxSize = 4096, ttlMillis = 24L * 60 * 60 * 1000),
) {
    private val dao: BeaconDao =
        Room.databaseBuilder(
            context.applicationContext,
            BeaconDatabase::class.java,
            "networklocation-beacons",
        ).setDriver(AndroidSQLiteDriver()).build().beaconDao()

    /** Fetch cached fixes for [ids], checking memory first then the DB. */
    suspend fun get(ids: List<BeaconId>): Map<BeaconId, BeaconFix> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<BeaconId, BeaconFix>(ids.size)
        val misses = ArrayList<BeaconId>()
        for (id in ids) {
            val hit = memory.get(id.key())
            if (hit != null) out[id] = hit else misses.add(id)
        }
        if (misses.isNotEmpty()) {
            val byKey = misses.associateBy { it.key() }
            for (row in dao.byKeys(misses.map { it.key() })) {
                val id = byKey[row.key] ?: continue
                val fix = BeaconFix(id, row.latitude, row.longitude, row.accuracyMeters)
                memory.put(row.key, fix)
                out[id] = fix
            }
        }
        return out
    }

    /** Persist newly-resolved [fixes] into both tiers. */
    suspend fun put(fixes: List<BeaconFix>) {
        if (fixes.isEmpty()) return
        val now = System.currentTimeMillis()
        for (f in fixes) memory.put(f.id.key(), f)
        dao.upsertAll(
            fixes.map {
                CachedBeaconEntity(it.id.key(), it.latitude, it.longitude, it.accuracyMeters, now)
            },
        )
    }
}
