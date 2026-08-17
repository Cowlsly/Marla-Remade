package com.vayunmathur.maps.util
import com.vayunmathur.maps.R
import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class ZoneDownloadManager(private val context: Context) {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    enum class ZoneStatus { NOT_STARTED, DOWNLOADING, FINISHED }

    /**
     * A Flow that emits a Map of all zones currently being downloaded.
     * Key: Zone ID, Value: Progress (0.0 to 1.0).
     */
    fun getDownloadingZonesFlow(): Flow<Map<Int, Float>> = flow {
        while (true) {
            val progressMap = mutableMapOf<Int, Double>()
            val activeZones = mutableSetOf<Int>()

            // 1. Get current system status for all zone downloads
            val query = DownloadManager.Query()
            // .use { } guarantees the Cursor is closed even if a column read
            // below throws — otherwise we leak a Cursor on every poll cycle.
            downloadManager.query(query).use { cursor ->
                // Map to track which parts of which zones we've found in the system
                val foundParts = mutableMapOf<Int, MutableSet<String>>()

                while (cursor.moveToNext()) {
                    val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                    if (title.startsWith("Map Zone ")) {
                        // Title format: "Map Zone $zoneId ($part)"
                        val zonePartString = title.removePrefix("Map Zone ")
                        val zoneId = zonePartString.substringBefore(" ").toIntOrNull()
                        val partName = zonePartString.substringAfter("(", "").substringBefore(")")

                        if (zoneId != null && partName.isNotEmpty()) {
                            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                            val progress = when (status) {
                                DownloadManager.STATUS_SUCCESSFUL -> 1.0
                                DownloadManager.STATUS_FAILED -> 0.0
                                else -> if (total > 0) downloaded.toDouble() / total.toDouble() else 0.0
                            }

                            progressMap[zoneId] = progressMap.getOrDefault(zoneId, 0.0) + progress
                            foundParts.getOrPut(zoneId) { mutableSetOf() }.add(partName)

                            if (status == DownloadManager.STATUS_RUNNING ||
                                status == DownloadManager.STATUS_PENDING ||
                                status == DownloadManager.STATUS_PAUSED) {
                                activeZones.add(zoneId)
                            }
                        }
                    }
                }

                // 2. Cross-reference with disk for the pmtiles part not found in
                // DownloadManager (e.g. if system cleared the record but file exists)
                activeZones.forEach { zoneId ->
                    val foundInDM = foundParts[zoneId] ?: emptySet()
                    if ("Map" !in foundInDM) {
                        val file = File(context.getExternalFilesDir(null), "zone_$zoneId.pmtiles")
                        if (file.exists()) {
                            progressMap[zoneId] = progressMap.getOrDefault(zoneId, 0.0) + 1.0
                        }
                    }
                }
            }

            val finalProgressMap = activeZones.mapNotNull { zoneId ->
                val avg = progressMap[zoneId] ?: 0.0
                if (avg < 0.999) zoneId to avg.toFloat() else null
            }.toMap()

            emit(finalProgressMap)
            delay(1000)
        }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    /**
     * Deletes a zone file and cancels any active downloads for that zone.
     */
    fun deleteZone(zoneId: Int) {
        // 1. Cancel active or pending downloads in the system
        val query = DownloadManager.Query()
        downloadManager.query(query).use { cursor ->
            while (cursor.moveToNext()) {
                val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                if (title.startsWith("Map Zone $zoneId ")) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    downloadManager.remove(id)
                }
            }
        }

        // 2. Remove the files from disk. The transit index (P11c) is an
        // optional second pack part alongside the pmtiles tiles.
        val files = listOf(
            "zone_$zoneId.pmtiles",
            "zone_$zoneId.transit"
        )
        files.forEach { fileName ->
            val file = File(context.getExternalFilesDir(null), fileName)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    fun getDownloadedZonesFlow(): Flow<List<Int>> = flow {
        while (true) {
            emit(getDownloadedZones())
            delay(2000) // Poll every 2 seconds
        }
    }
        .distinctUntilChanged() // Only emit if the list actually changes
        .conflate()             // Drop intermediate updates if the UI is slow
        .flowOn(Dispatchers.IO) // Run the disk/DB checks on a background thread

    fun getDownloadedZones(): List<Int> {
        // Build the set of currently-downloading zone IDs ONCE per poll instead
        // of opening a DownloadManager cursor 64 times (one per zone).
        val downloadingZones = activeDownloadZoneIds()
        return (0..63).filter { zoneId ->
            getZoneStatus(zoneId, downloadingZones) == ZoneStatus.FINISHED
        }
    }

    /** Single-pass scan of the DownloadManager for zone download titles. */
    private fun activeDownloadZoneIds(): Set<Int> {
        val active = mutableSetOf<Int>()
        downloadManager.query(DownloadManager.Query()).use { cursor ->
            while (cursor.moveToNext()) {
                val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                    ?: continue
                if (!title.startsWith("Map Zone ")) continue
                val id = title.removePrefix("Map Zone ").substringBefore(" ").toIntOrNull() ?: continue
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_RUNNING ||
                    status == DownloadManager.STATUS_PAUSED ||
                    status == DownloadManager.STATUS_PENDING) {
                    active.add(id)
                }
            }
        }
        return active
    }

    fun getZoneStatus(zoneId: Int, activeIds: Set<Int> = activeDownloadZoneIds()): ZoneStatus {
        val pmtilesFile = File(context.getExternalFilesDir(null), "zone_$zoneId.pmtiles")
        if (pmtilesFile.exists()) return ZoneStatus.FINISHED

        return if (zoneId in activeIds) ZoneStatus.DOWNLOADING else ZoneStatus.NOT_STARTED
    }

    fun startDownload(zoneId: Int) {
        deleteZone(zoneId)
        val fileName = "zone_$zoneId.pmtiles"
        val request = DownloadManager.Request("https://data.vayunmathur.com/zone_$zoneId.pmtiles".toUri())
            .setTitle(context.getString(R.string.map_zone_download_title, zoneId))
            .setDescription(context.getString(R.string.map_zone_download_desc))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, fileName)
            .setAllowedOverMetered(true)

        downloadManager.enqueue(request)

        // Optional second pack part (P11c): the per-region offline transit index
        // (RAPTOR data from scripts/maps/gtfs_ingest). Best-effort — zones with
        // no transit coverage 404 and are silently ignored (getZoneStatus keys
        // FINISHED off the pmtiles part only). The title keeps the
        // "Map Zone $id ($part)" convention so progress tracking recognises it.
        val transitFile = "zone_$zoneId.transit"
        val transitRequest =
            DownloadManager.Request("https://data.vayunmathur.com/zone_$zoneId.transit".toUri())
                .setTitle("Map Zone $zoneId (Transit)")
                .setDescription(context.getString(R.string.map_zone_download_desc))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, null, transitFile)
                .setAllowedOverMetered(true)

        downloadManager.enqueue(transitRequest)
    }
}