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

    /** Status of the single global routing graph (P16), independent of zones. */
    enum class GraphStatus { NOT_STARTED, DOWNLOADING, FINISHED }

    companion object {
        private const val HOST = "https://data.vayunmathur.com"

        /**
         * The SINGLE GLOBAL routing-graph files consumed by the Rust router
         * (maps/src/main/rust/src/graph.rs). Downloaded once as a whole, NOT
         * per Morton zone — the routing graph is no longer zoned (P16). The
         * per-zone offline TILE packs (`zone_$id.pmtiles`) and the P11 transit
         * index (`zone_$id.transit`) remain per-zone and are handled separately.
         */
        val GRAPH_FILES = listOf(
            "metadata.bin",
            "nodes.bin",
            "edges.bin",
            "road_names.bin",
            "transit_voyages.bin",
            "transit_attributes.bin",
            "lanes.bin",
        )

        // Files that must exist for graph.rs to load a usable graph; the rest
        // (transit_*/lanes) are optional and 404 gracefully for metros without
        // that data, so FINISHED keys off these three only.
        private val GRAPH_REQUIRED = listOf("metadata.bin", "nodes.bin", "edges.bin")

        // DownloadManager title prefix for graph parts. Deliberately distinct
        // from the "Map Zone " prefix so the per-zone progress/scan logic never
        // picks these up.
        private const val GRAPH_TITLE_PREFIX = "Routing Graph"
    }

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

    // --- Single global routing graph (P16) -----------------------------------

    /** True once the mandatory global graph files are present on disk. */
    fun isGraphDownloaded(): Boolean =
        GRAPH_REQUIRED.all { File(context.getExternalFilesDir(null), it).exists() }

    /** Whether any graph part is currently enqueued/running in DownloadManager. */
    private fun graphDownloadActive(): Boolean {
        downloadManager.query(DownloadManager.Query()).use { cursor ->
            while (cursor.moveToNext()) {
                val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                    ?: continue
                if (!title.startsWith(GRAPH_TITLE_PREFIX)) continue
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_RUNNING ||
                    status == DownloadManager.STATUS_PAUSED ||
                    status == DownloadManager.STATUS_PENDING) {
                    return true
                }
            }
        }
        return false
    }

    fun getGraphStatus(): GraphStatus = when {
        isGraphDownloaded() -> GraphStatus.FINISHED
        graphDownloadActive() -> GraphStatus.DOWNLOADING
        else -> GraphStatus.NOT_STARTED
    }

    fun getGraphStatusFlow(): Flow<GraphStatus> = flow {
        while (true) {
            emit(getGraphStatus())
            delay(2000)
        }
    }
        .distinctUntilChanged()
        .conflate()
        .flowOn(Dispatchers.IO)

    /**
     * Download the single global routing graph into the base dir the Rust
     * router loads from. Enqueues one DownloadManager request per graph file;
     * optional parts (transit/lanes) that 404 for a given build are ignored.
     */
    fun startGraphDownload() {
        deleteGraph()
        GRAPH_FILES.forEach { fileName ->
            val request = DownloadManager.Request("$HOST/$fileName".toUri())
                .setTitle("$GRAPH_TITLE_PREFIX ($fileName)")
                .setDescription(context.getString(R.string.routing_graph_download_desc))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, null, fileName)
                .setAllowedOverMetered(true)
            downloadManager.enqueue(request)
        }
    }

    /** Cancel any in-flight graph downloads and remove the graph files. */
    fun deleteGraph() {
        downloadManager.query(DownloadManager.Query()).use { cursor ->
            while (cursor.moveToNext()) {
                val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                    ?: continue
                if (title.startsWith(GRAPH_TITLE_PREFIX)) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    downloadManager.remove(id)
                }
            }
        }
        GRAPH_FILES.forEach { fileName ->
            val file = File(context.getExternalFilesDir(null), fileName)
            if (file.exists()) {
                file.delete()
            }
        }
    }
}