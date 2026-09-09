package com.vayunmathur.networklocation.provider

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import com.vayunmathur.networklocation.BeaconFix
import com.vayunmathur.networklocation.BeaconId
import com.vayunmathur.networklocation.DevicePosition
import com.vayunmathur.networklocation.NetworkLocationNative
import com.vayunmathur.networklocation.cache.BeaconCache
import com.vayunmathur.networklocation.cell.NearbyCells
import com.vayunmathur.networklocation.wifi.NearbyWifi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The periodic fix loop: scan nearby radios, resolve them to coordinates against the
 * cache and the offline WPSDB stores, and estimate the device position with the native
 * solver. Reconstructs GrapheneOS's LocationReportingTask.
 *
 * Resolution is entirely on-device. Beacons absent from both tiers are dropped, so a
 * fix is produced only from what the downloaded stores know; if they have not been
 * downloaded yet, no fix is produced at all.
 */
class LocationReportingTask(
    context: Context,
    private val onFix: (Location) -> Unit,
) {
    private val appContext = context.applicationContext
    private val wifi = NearbyWifi(appContext)
    private val cells = NearbyCells(appContext)
    private val cache = BeaconCache(appContext)
    private val offlineStore = OfflineBeaconStore(appContext)

    private var scope: CoroutineScope? = null

    fun start(intervalMillis: Long) {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        newScope.launch {
            while (isActive) {
                runCatching { reportOnce() }
                delay(intervalMillis.coerceAtLeast(MIN_INTERVAL_MS))
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        offlineStore.close()
    }

    private suspend fun reportOnce() {
        val position = estimate() ?: return
        onFix(position.toLocation())
    }

    /** Full pipeline for a single fix; null when there are no usable beacons. */
    private suspend fun estimate(): DevicePosition? {
        val wifiIds = wifi.scan()
        val cellIds = cells.scan()
        val allIds: List<BeaconId> = wifiIds + cellIds
        if (allIds.isEmpty()) return null

        val fixes = resolveFixes(wifiIds, cellIds, allIds)
        if (fixes.isEmpty()) return null

        val interleaved = DoubleArray(fixes.size * 3)
        for ((i, f) in fixes.withIndex()) {
            interleaved[i * 3] = f.latitude
            interleaved[i * 3 + 1] = f.longitude
            interleaved[i * 3 + 2] = f.accuracyMeters
        }
        val out = NetworkLocationNative.estimatePosition(interleaved) ?: return null
        return DevicePosition(out[0], out[1], out[2])
    }

    private suspend fun resolveFixes(
        wifiIds: List<BeaconId.Wifi>,
        cellIds: List<BeaconId.Cell>,
        allIds: List<BeaconId>,
    ): List<BeaconFix> {
        val cached = cache.get(allIds)
        val resolved = ArrayList(cached.values)

        val missingWifi = wifiIds.filter { it !in cached }
        val missingCell = cellIds.filter { it !in cached }

        // Offline store tier: the only source of new beacon coordinates. Hits are written
        // back into BeaconCache so the in-memory/Room tier front-runs later scans. Beacons
        // absent from the stores stay unresolved and are simply left out of the solve.
        if (missingWifi.isNotEmpty() || missingCell.isNotEmpty()) {
            val offline = withContext(Dispatchers.Default) {
                buildList {
                    if (missingWifi.isNotEmpty()) addAll(offlineStore.resolveWifi(missingWifi).values)
                    if (missingCell.isNotEmpty()) addAll(offlineStore.resolveCell(missingCell).values)
                }
            }
            if (offline.isNotEmpty()) {
                cache.put(offline)
                resolved.addAll(offline)
            }
        }
        return resolved
    }

    private fun DevicePosition.toLocation(): Location =
        Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = this@toLocation.latitude
            longitude = this@toLocation.longitude
            accuracy = accuracyMeters.toFloat()
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

    private companion object {
        const val MIN_INTERVAL_MS = 1_000L
    }
}
