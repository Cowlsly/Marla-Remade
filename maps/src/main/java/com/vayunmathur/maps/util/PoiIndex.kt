package com.vayunmathur.maps.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Offline reader for the P27 POI side files, memory-mapped from the app's
 * external files dir (the same dir the routing graph downloads into, see
 * [OfflineRouter] / MainActivity's InitialDownloadChecker):
 *
 *  * `poi_names.bin` — unique UTF-8 names, each NUL-terminated, concatenated in
 *    first-seen order. A `name_off` is the byte offset of a name's first byte;
 *    shared names resolve to the same offset.
 *  * `poi_index.bin` — a flat array of 14-byte little-endian records
 *    `{ int32 lat_e7; int32 lon_e7; uint32 name_off; uint16 type }`,
 *    `count = filesize / 14`, sorted ascending by the 64-bit Morton(lat,lon)
 *    key (the key itself is not stored).
 *
 * It exposes a name search (prefix/substring over the deduped name pool) and a
 * spatial nearest lookup, so a POI query can resolve locally without a Google
 * call. Everything is a harmless no-op until both files are present (they ship
 * from the same host as the graph); queries then return empty lists.
 */
object PoiIndex {
    private const val TAG = "PoiIndex"
    const val INDEX_FILE = "poi_index.bin"
    const val NAMES_FILE = "poi_names.bin"

    /** Bytes per record: int32 lat_e7 + int32 lon_e7 + uint32 name_off + uint16 type. */
    private const val RECORD_BYTES = 14

    /** Cap on candidates gathered before ranking, so a planet-sized pool can't
     *  blow up memory on a broad substring match. */
    private const val CANDIDATE_CAP = 2_000

    /** A single POI resolved from the index. */
    data class PoiRecord(
        val latE7: Int,
        val lonE7: Int,
        val type: Int,
        val name: String,
    ) {
        val lat: Double get() = latE7 / 1e7
        val lon: Double get() = lonE7 / 1e7
    }

    private var index: MappedByteBuffer? = null
    private var names: MappedByteBuffer? = null
    private var namesLen: Int = 0
    private var count: Int = 0
    private var loaded = false
    private var tried = false

    /** True once both side files were mapped successfully. */
    val available: Boolean get() = loaded

    /** Number of POI records available offline (0 when not loaded). */
    val recordCount: Int get() = count

    /**
     * Map both side files from [Context.getExternalFilesDir]. Idempotent and
     * safe to call repeatedly; returns false (and stays a no-op) when either
     * file is missing. Not retried automatically after a failure unless
     * [reload] is called (e.g. after the download completes).
     */
    @Synchronized
    fun initialize(context: Context): Boolean {
        if (loaded) return true
        if (tried) return false
        tried = true
        val dir = context.getExternalFilesDir(null) ?: return false
        val indexFile = File(dir, INDEX_FILE)
        val namesFile = File(dir, NAMES_FILE)
        if (!indexFile.isFile || !namesFile.isFile) {
            Log.d(TAG, "POI side files absent (index=${indexFile.exists()} names=${namesFile.exists()})")
            return false
        }
        return try {
            index = mapReadOnly(indexFile).also { it.order(ByteOrder.LITTLE_ENDIAN) }
            val namesBuf = mapReadOnly(namesFile)
            names = namesBuf
            namesLen = namesBuf.capacity()
            count = (indexFile.length() / RECORD_BYTES).toInt()
            loaded = true
            Log.d(TAG, "Loaded $count POI records, names=${namesLen}B")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to map POI side files", e)
            index = null; names = null; loaded = false
            false
        }
    }

    /** Force a re-map after the side files finish downloading. */
    @Synchronized
    fun reload(context: Context): Boolean {
        loaded = false
        tried = false
        index = null
        names = null
        return initialize(context)
    }

    private fun mapReadOnly(file: File): MappedByteBuffer =
        RandomAccessFile(file, "r").use { raf ->
            raf.channel.use { ch ->
                // Demo side files fit in a single mapping; cap defensively at 2GB.
                val len = minOf(ch.size(), Int.MAX_VALUE.toLong())
                ch.map(FileChannel.MapMode.READ_ONLY, 0, len)
            }
        }

    /** Read the NUL-terminated UTF-8 name that starts at byte [off], or null. */
    private fun nameAt(off: Int): String? {
        val buf = names ?: return null
        if (off < 0 || off >= namesLen) return null
        var end = off
        while (end < namesLen && buf.get(end).toInt() != 0) end++
        val n = end - off
        if (n <= 0) return null
        val bytes = ByteArray(n)
        // Absolute bulk get keeps the shared buffer's position untouched.
        val dup = buf.duplicate()
        dup.position(off)
        dup.get(bytes, 0, n)
        return String(bytes, Charsets.UTF_8)
    }

    private fun recLatE7(i: Int): Int = index!!.getInt(i * RECORD_BYTES)
    private fun recLonE7(i: Int): Int = index!!.getInt(i * RECORD_BYTES + 4)
    private fun recNameOff(i: Int): Int = index!!.getInt(i * RECORD_BYTES + 8)
    private fun recType(i: Int): Int = index!!.getShort(i * RECORD_BYTES + 12).toInt() and 0xFFFF

    /**
     * Case-insensitive prefix/substring search over the deduped name pool,
     * returning matching POIs ranked prefix-first then by distance to
     * ([nearLat],[nearLon]). Returns an empty list when nothing matches or the
     * index isn't loaded.
     */
    @Synchronized
    fun searchByName(
        query: String,
        nearLat: Double,
        nearLon: Double,
        limit: Int = 20,
    ): List<PoiRecord> {
        if (!loaded) return emptyList()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        // Pass 1: walk the name pool once, recording matching offsets and their
        // rank (0 = prefix match, 1 = substring). Decoding each unique name once
        // is the dedup win the side-file layout is designed for.
        val buf = names ?: return emptyList()
        val matchRank = HashMap<Int, Int>()
        var pos = 0
        while (pos < namesLen) {
            var end = pos
            while (end < namesLen && buf.get(end).toInt() != 0) end++
            if (end > pos) {
                val name = nameAt(pos)
                if (name != null) {
                    val lower = name.lowercase()
                    if (lower.startsWith(q)) matchRank[pos] = 0
                    else if (lower.contains(q)) matchRank[pos] = 1
                }
            }
            pos = end + 1
        }
        if (matchRank.isEmpty()) return emptyList()

        // Pass 2: scan records, keeping those whose name offset matched.
        val out = ArrayList<Ranked>(minOf(CANDIDATE_CAP, count))
        var i = 0
        while (i < count && out.size < CANDIDATE_CAP) {
            val off = recNameOff(i)
            val rank = matchRank[off]
            if (rank != null) {
                val name = nameAt(off) ?: ""
                val latE7 = recLatE7(i)
                val lonE7 = recLonE7(i)
                out.add(
                    Ranked(
                        PoiRecord(latE7, lonE7, recType(i), name),
                        rank,
                        distanceSq(latE7 / 1e7, lonE7 / 1e7, nearLat, nearLon),
                    )
                )
            }
            i++
        }
        out.sortWith(compareBy({ it.rank }, { it.distSq }))
        return out.take(limit).map { it.record }
    }

    /**
     * Nearest POIs to ([lat],[lon]) within [maxMeters], closest first (bbox
     * pre-filter + squared-distance sort). Names are decoded only for the
     * returned winners. Empty when the index isn't loaded.
     */
    @Synchronized
    fun nearest(
        lat: Double,
        lon: Double,
        limit: Int = 20,
        maxMeters: Double = 250.0,
    ): List<PoiRecord> {
        if (!loaded) return emptyList()
        val dLat = maxMeters / 111_320.0
        val cosLat = Math.cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
        val dLon = maxMeters / (111_320.0 * cosLat)
        val minLatE7 = ((lat - dLat) * 1e7).toInt()
        val maxLatE7 = ((lat + dLat) * 1e7).toInt()
        val minLonE7 = ((lon - dLon) * 1e7).toInt()
        val maxLonE7 = ((lon + dLon) * 1e7).toInt()

        val hits = ArrayList<Hit>()
        var i = 0
        while (i < count) {
            val latE7 = recLatE7(i)
            val lonE7 = recLonE7(i)
            if (latE7 in minLatE7..maxLatE7 && lonE7 in minLonE7..maxLonE7) {
                hits.add(Hit(i, distanceSq(latE7 / 1e7, lonE7 / 1e7, lat, lon)))
            }
            i++
        }
        hits.sortBy { it.distSq }
        return hits.take(limit).map { h ->
            PoiRecord(
                recLatE7(h.idx),
                recLonE7(h.idx),
                recType(h.idx),
                nameAt(recNameOff(h.idx)) ?: "",
            )
        }
    }

    private class Ranked(val record: PoiRecord, val rank: Int, val distSq: Double)
    private class Hit(val idx: Int, val distSq: Double)

    /** Cheap squared planar distance (deg², lon scaled by cos lat) for ranking. */
    private fun distanceSq(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val cosLat = Math.cos(Math.toRadians((aLat + bLat) / 2.0))
        val dLat = aLat - bLat
        val dLon = (aLon - bLon) * cosLat
        return dLat * dLat + dLon * dLon
    }
}
