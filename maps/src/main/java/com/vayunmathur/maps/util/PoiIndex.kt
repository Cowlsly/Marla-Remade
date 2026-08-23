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
 *  * `poi_attrs.bin` — OPTIONAL attribute sidecar (opening hours, phone,
 *    website, address, cuisine, wheelchair), indexed by the ORDINAL of the
 *    matching `poi_index.bin` record. See `scripts/maps/osm_ingest/src/poi_attrs.rs`
 *    for the layout.
 *
 * It exposes a name search (prefix/substring over the deduped name pool) and a
 * spatial nearest lookup, so a POI query can resolve locally without a Google
 * call. Everything is a harmless no-op until both files are present (they ship
 * from the same host as the graph); queries then return empty lists. The sidecar
 * is separately optional: an install that has the index but not the attributes
 * keeps working, and every attribute reads as null.
 */
object PoiIndex {
    private const val TAG = "PoiIndex"
    const val INDEX_FILE = "poi_index.bin"
    const val NAMES_FILE = "poi_names.bin"
    const val ATTRS_FILE = "poi_attrs.bin"

    /** Bytes per record: int32 lat_e7 + int32 lon_e7 + uint32 name_off + uint16 type. */
    private const val RECORD_BYTES = 14

    /** Cap on candidates gathered before ranking, so a planet-sized pool can't
     *  blow up memory on a broad substring match. */
    private const val CANDIDATE_CAP = 2_000

    // --- poi_attrs.bin layout (see poi_attrs.rs) ---------------------------
    private val ATTRS_MAGIC =
        byteArrayOf('M'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(), 'A'.code.toByte())
    private const val ATTRS_VERSION = 1
    private const val ATTRS_HEADER_BYTES = 12
    /** `attr_off` for a POI that carries no attributes. */
    private const val NO_ATTRS = -1

    private const val KEY_OPENING_HOURS = 1
    private const val KEY_PHONE = 2
    private const val KEY_WEBSITE = 3
    private const val KEY_HOUSENUMBER = 4
    private const val KEY_STREET = 5
    private const val KEY_CITY = 6
    private const val KEY_POSTCODE = 7
    private const val KEY_CUISINE = 8
    private const val KEY_WHEELCHAIR = 9

    /** A single POI resolved from the index. */
    data class PoiRecord(
        val latE7: Int,
        val lonE7: Int,
        val type: Int,
        val name: String,
        /**
         * Position in `poi_index.bin`, which is also this POI's key into the
         * attribute sidecar. -1 for a record not read from the index.
         */
        val ordinal: Int = -1,
    ) {
        val lat: Double get() = latE7 / 1e7
        val lon: Double get() = lonE7 / 1e7
    }

    /**
     * The OSM tags a place sheet wants, for one POI. Every field is null when OSM
     * did not tag it — which is the common case for most of them.
     */
    data class PoiAttributes(
        val openingHours: String? = null,
        val phone: String? = null,
        val website: String? = null,
        val houseNumber: String? = null,
        val street: String? = null,
        val city: String? = null,
        val postcode: String? = null,
        val cuisine: String? = null,
        val wheelchair: String? = null,
    ) {
        /**
         * The `addr:*` tags as one display line, or null when there are none.
         *
         * House number joins the street with a space and the rest with commas, so a
         * partial address (very common in OSM — a street with no city) still reads
         * as an address rather than as a fragment.
         */
        val address: String?
            get() {
                val line = listOfNotNull(houseNumber, street)
                    .joinToString(" ")
                    .ifEmpty { null }
                return listOfNotNull(line, city, postcode)
                    .joinToString(", ")
                    .ifEmpty { null }
            }

        val isEmpty: Boolean
            get() = openingHours == null && phone == null && website == null &&
                address == null && cuisine == null && wheelchair == null
    }

    private var index: MappedByteBuffer? = null
    private var names: MappedByteBuffer? = null
    private var namesLen: Int = 0
    private var count: Int = 0
    private var loaded = false
    private var tried = false

    /** The attribute sidecar, or null when it is absent or does not match. */
    private var attrs: MappedByteBuffer? = null
    /** Byte offset of the blob, i.e. just past the offset array. */
    private var attrsBlobStart: Int = 0

    /** True once both side files were mapped successfully. */
    val available: Boolean get() = loaded

    /** True when the optional attribute sidecar is mapped and usable. */
    val attributesAvailable: Boolean get() = attrs != null

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
            // Separate and optional: a failure here leaves the index perfectly
            // usable, just without attributes.
            openAttrs(File(dir, ATTRS_FILE))
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
        attrs = null
        return initialize(context)
    }

    /**
     * Map the attribute sidecar, leaving [attrs] null if anything is off.
     *
     * The record-count check is the one that matters: the sidecar joins to the
     * index purely by position, so a sidecar from a different build would hand
     * every place someone else's phone number. Refusing the file is the only safe
     * response, and there is no way to detect the mismatch later.
     */
    private fun openAttrs(file: File) {
        attrs = null
        if (!file.isFile) {
            Log.d(TAG, "POI attribute sidecar absent")
            return
        }
        try {
            val buf = mapReadOnly(file).also { it.order(ByteOrder.LITTLE_ENDIAN) }
            if (buf.capacity() < ATTRS_HEADER_BYTES) {
                Log.w(TAG, "$ATTRS_FILE is truncated")
                return
            }
            for (i in ATTRS_MAGIC.indices) {
                if (buf.get(i) != ATTRS_MAGIC[i]) {
                    Log.w(TAG, "$ATTRS_FILE has the wrong magic")
                    return
                }
            }
            // Not a gate: the length prefixes let this reader step over a key added
            // by a later version, so a newer file is readable. A version it has
            // never heard of is worth a line in the log all the same.
            val version = buf.get(4).toInt()
            if (version != ATTRS_VERSION) {
                Log.d(TAG, "$ATTRS_FILE is version $version, expected $ATTRS_VERSION")
            }
            val attrCount = buf.getInt(8)
            if (attrCount != count) {
                Log.w(TAG, "$ATTRS_FILE has $attrCount slots but the index has $count; ignoring it")
                return
            }
            val blobStart = ATTRS_HEADER_BYTES + 4 * attrCount
            if (blobStart > buf.capacity()) {
                Log.w(TAG, "$ATTRS_FILE offset array runs past the file")
                return
            }
            attrsBlobStart = blobStart
            attrs = buf
            Log.d(TAG, "Loaded POI attributes for $attrCount record(s)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to map $ATTRS_FILE", e)
        }
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
                        PoiRecord(latE7, lonE7, recType(i), name, i),
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
                h.idx,
            )
        }
    }

    /**
     * All POIs whose coordinate falls inside the [west]..[east] × [south]..[north]
     * bounding box, capped at [cap] (a linear scan over the flat record array —
     * cheap even at ~283k records for a per-idle viewport refresh). Names are
     * decoded for each returned record. Empty when the index isn't loaded; used
     * to drive the ambient offline pin overlay (P29).
     */
    @Synchronized
    fun inViewport(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        cap: Int = 300,
    ): List<PoiRecord> {
        if (!loaded || cap <= 0) return emptyList()
        val minLatE7 = (south * 1e7).toInt()
        val maxLatE7 = (north * 1e7).toInt()
        val minLonE7 = (west * 1e7).toInt()
        val maxLonE7 = (east * 1e7).toInt()
        val out = ArrayList<PoiRecord>(minOf(cap, count))
        var i = 0
        while (i < count && out.size < cap) {
            val latE7 = recLatE7(i)
            val lonE7 = recLonE7(i)
            if (latE7 in minLatE7..maxLatE7 && lonE7 in minLonE7..maxLonE7) {
                out.add(PoiRecord(latE7, lonE7, recType(i), nameAt(recNameOff(i)) ?: "", i))
            }
            i++
        }
        return out
    }

    private class Ranked(val record: PoiRecord, val rank: Int, val distSq: Double)
    private class Hit(val idx: Int, val distSq: Double)

    /**
     * The attributes of the [ordinal]th index record, or null when there are none.
     *
     * Two indexed reads and a short decode, no scan. Deliberately NOT called while
     * building viewport or search result lists, where it would decode hundreds of
     * records nobody looks at.
     *
     * Every offset is treated as untrusted. The file arrives over the network and a
     * truncated download must read as "no attributes", not throw out of a tap.
     */
    @Synchronized
    fun attributesAt(ordinal: Int): PoiAttributes? {
        val buf = attrs ?: return null
        if (ordinal < 0 || ordinal >= count) return null
        val off = buf.getInt(ATTRS_HEADER_BYTES + 4 * ordinal)
        if (off == NO_ATTRS || off < 0) return null
        // Bounded before the add, not after: `attrsBlobStart + off` can wrap negative
        // for a large positive off, and a negative index passes an `at + 2 > capacity`
        // test on its way to an IndexOutOfBoundsException.
        val blobLen = buf.capacity() - attrsBlobStart
        if (off > blobLen - 2) return null
        val at = attrsBlobStart + off
        val bodyLen = buf.getShort(at).toInt() and 0xFFFF
        val body = at + 2
        if (bodyLen > buf.capacity() - body) return null
        return decodeAttributes(buf, body, body + bodyLen)
    }

    /**
     * The attributes of the POI named [name] nearest to ([lat],[lon]), or null.
     *
     * This is the join a tapped tile feature needs, and the reason it is not an
     * exact coordinate match: the `ma_pois` tile layer quantises every point to its
     * tile's 4096-step grid (about 0.15 m at z16), so a tap's coordinate is close to
     * the side file's `lat_e7`/`lon_e7` but never equal to it.
     *
     * The name is matched too, not just the distance. A mall and a cafe inside it
     * can share a coordinate to within a metre, and attaching one's phone number to
     * the other is the kind of wrong that looks right.
     *
     * Costs one [nearest] call, which is a linear scan of the whole record array —
     * the same cost model [inViewport] documents and runs per viewport idle. If that
     * ever stops being affordable it is a limitation of all three, not of this one.
     */
    @Synchronized
    fun attributesNear(
        lat: Double,
        lon: Double,
        name: String,
        maxMeters: Double = 25.0,
    ): PoiAttributes? {
        if (attrs == null || name.isBlank()) return null
        val match = nearest(lat, lon, limit = 8, maxMeters = maxMeters)
            .firstOrNull { it.name == name }
            ?: return null
        return attributesAt(match.ordinal)
    }

    /**
     * Walk one record's `u8 key, u16 len, value` fields.
     *
     * A key this build does not know is stepped over using its length rather than
     * abandoning the record, which is the whole reason the values are
     * length-prefixed: a device on an older build must still read the keys it does
     * understand out of a newer file.
     */
    private fun decodeAttributes(buf: MappedByteBuffer, from: Int, to: Int): PoiAttributes? {
        var openingHours: String? = null
        var phone: String? = null
        var website: String? = null
        var houseNumber: String? = null
        var street: String? = null
        var city: String? = null
        var postcode: String? = null
        var cuisine: String? = null
        var wheelchair: String? = null

        var i = from
        while (i + 3 <= to) {
            val key = buf.get(i).toInt() and 0xFF
            val len = buf.getShort(i + 1).toInt() and 0xFFFF
            val start = i + 3
            if (start + len > to) break
            // Only decode the bytes of a key we are going to keep.
            val value: String? = when (key) {
                KEY_OPENING_HOURS, KEY_PHONE, KEY_WEBSITE, KEY_HOUSENUMBER, KEY_STREET,
                KEY_CITY, KEY_POSTCODE, KEY_CUISINE, KEY_WHEELCHAIR ->
                    stringAt(buf, start, len)
                else -> null
            }
            when (key) {
                KEY_OPENING_HOURS -> openingHours = value
                KEY_PHONE -> phone = value
                KEY_WEBSITE -> website = value
                KEY_HOUSENUMBER -> houseNumber = value
                KEY_STREET -> street = value
                KEY_CITY -> city = value
                KEY_POSTCODE -> postcode = value
                KEY_CUISINE -> cuisine = value
                KEY_WHEELCHAIR -> wheelchair = value
            }
            i = start + len
        }

        val decoded = PoiAttributes(
            openingHours, phone, website, houseNumber, street, city, postcode, cuisine, wheelchair,
        )
        return decoded.takeUnless { it.isEmpty }
    }

    private fun stringAt(buf: MappedByteBuffer, off: Int, len: Int): String? {
        if (len <= 0) return null
        val bytes = ByteArray(len)
        // Absolute reads via a duplicate, so the shared buffer's position is untouched.
        val dup = buf.duplicate()
        dup.position(off)
        dup.get(bytes, 0, len)
        return String(bytes, Charsets.UTF_8)
    }

    /** Cheap squared planar distance (deg², lon scaled by cos lat) for ranking. */
    private fun distanceSq(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val cosLat = Math.cos(Math.toRadians((aLat + bLat) / 2.0))
        val dLat = aLat - bLat
        val dLon = (aLon - bLon) * cosLat
        return dLat * dLat + dLon * dLon
    }
}
