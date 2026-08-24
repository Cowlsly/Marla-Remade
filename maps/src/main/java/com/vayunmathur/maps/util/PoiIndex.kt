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
 *    key (the key itself is not stored — [spatialFromE7] recomputes it).
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
 *
 * **The spatial queries binary-search; they do not scan.** They used to walk all
 * `count` records, which was imperceptible over a California extract and ANR'd the
 * app on a planet-wide one — 22.6 M records on the main thread inside a tap
 * handler. `poi_index.bin` was already sorted by Morton key; this reader simply
 * never used it. See [Mapped.forEachInBbox] for the bound that replaced the scan,
 * and its caveat.
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

    /**
     * One consistent set of mappings, published as a unit.
     *
     * Every query used to be `@Synchronized` on this object, which meant a lookup for a tapped
     * pin also blocked a background viewport refresh and vice versa — for the duration of a
     * full-file scan. The mapped state is immutable once built, so the queries need no lock at
     * all: they read [mapped] once into a local and work from that. Only [reload] mutates, and
     * a query already in flight keeps using the snapshot it started with rather than seeing the
     * buffers swapped underneath it.
     */
    private class Mapped(
        val index: MappedByteBuffer,
        val names: MappedByteBuffer,
        val namesLen: Int,
        val count: Int,
        /** The attribute sidecar, or null when it is absent or does not match. */
        val attrs: MappedByteBuffer?,
        /** Byte offset of the attribute blob, i.e. just past the offset array. */
        val attrsBlobStart: Int,
    ) {
        fun latE7(i: Int): Int = index.getInt(i * RECORD_BYTES)
        fun lonE7(i: Int): Int = index.getInt(i * RECORD_BYTES + 4)
        fun nameOff(i: Int): Int = index.getInt(i * RECORD_BYTES + 8)
        fun type(i: Int): Int = index.getShort(i * RECORD_BYTES + 12).toInt() and 0xFFFF

        /** The Morton key the file is sorted by, recomputed from the record's coordinate. */
        fun spatialAt(i: Int): Long = spatialFromE7(latE7(i), lonE7(i))

        fun record(i: Int): PoiRecord =
            PoiRecord(latE7(i), lonE7(i), type(i), nameAt(nameOff(i)) ?: "", i)

        /** Read the NUL-terminated UTF-8 name that starts at byte [off], or null. */
        fun nameAt(off: Int): String? {
            if (off < 0 || off >= namesLen) return null
            var end = off
            while (end < namesLen && names.get(end).toInt() != 0) end++
            val n = end - off
            if (n <= 0) return null
            val bytes = ByteArray(n)
            // Absolute bulk get keeps the shared buffer's position untouched.
            val dup = names.duplicate()
            dup.position(off)
            dup.get(bytes, 0, n)
            return String(bytes, Charsets.UTF_8)
        }

        /** Ordinal of the first record whose key is >= [key], or [count]. */
        fun lowerBound(key: Long): Int {
            var lo = 0
            var hi = count
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                // Unsigned: half the planet's keys have bit 63 set, and a signed compare here
                // walks off the front of the northern hemisphere.
                if (java.lang.Long.compareUnsigned(spatialAt(mid), key) < 0) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /**
         * Visit every record inside the bbox in file order, stopping early when [onHit]
         * returns false.
         *
         * Cost is the box's Morton span — a binary search to its lower bound, then a walk to
         * its upper bound — rather than [count]. That span is not the box: a box straddling the
         * equator or the prime meridian flips a top-level Z-curve bit and covers most of the
         * key space, so those degenerate back toward a full walk. `poi_spatial.bin` makes the
         * lookup exactly cell-local; this is the bound available without a format change, and
         * it is what takes a POI tap from 22.6 M records to a local handful.
         */
        fun forEachInBbox(
            minLatE7: Int,
            maxLatE7: Int,
            minLonE7: Int,
            maxLonE7: Int,
            onHit: (ordinal: Int, latE7: Int, lonE7: Int) -> Boolean,
        ) {
            if (minLatE7 > maxLatE7 || minLonE7 > maxLonE7) return
            val (first, last) = spatialRangeForBbox(minLatE7, maxLatE7, minLonE7, maxLonE7)
            var i = lowerBound(first)
            while (i < count) {
                if (java.lang.Long.compareUnsigned(spatialAt(i), last) > 0) return
                val latE7 = latE7(i)
                val lonE7 = lonE7(i)
                if (latE7 in minLatE7..maxLatE7 && lonE7 in minLonE7..maxLonE7 &&
                    !onHit(i, latE7, lonE7)
                ) {
                    return
                }
                i++
            }
        }
    }

    @Volatile
    private var mapped: Mapped? = null
    private var tried = false

    /** True once both side files were mapped successfully. */
    val available: Boolean get() = mapped != null

    /** True when the optional attribute sidecar is mapped and usable. */
    val attributesAvailable: Boolean get() = mapped?.attrs != null

    /** Number of POI records available offline (0 when not loaded). */
    val recordCount: Int get() = mapped?.count ?: 0

    /**
     * Map both side files from [Context.getExternalFilesDir]. Idempotent and
     * safe to call repeatedly; returns false (and stays a no-op) when either
     * file is missing. Not retried automatically after a failure unless
     * [reload] is called (e.g. after the download completes).
     */
    @Synchronized
    fun initialize(context: Context): Boolean {
        if (mapped != null) return true
        if (tried) return false
        tried = true
        val dir = context.getExternalFilesDir(null) ?: return false
        return open(dir)
    }

    /** Force a re-map after the side files finish downloading. */
    @Synchronized
    fun reload(context: Context): Boolean {
        val dir = context.getExternalFilesDir(null) ?: return false
        return reload(dir)
    }

    /**
     * Re-map from an explicit directory.
     *
     * Also the seam `PoiIndexTest` maps fixtures through: the production entry point needs a
     * `Context` only to find the directory, and a unit test has a temp dir and no Context.
     */
    @Synchronized
    internal fun reload(dir: File): Boolean {
        mapped = null
        tried = true
        return open(dir)
    }

    @Synchronized
    private fun open(dir: File): Boolean {
        val indexFile = File(dir, INDEX_FILE)
        val namesFile = File(dir, NAMES_FILE)
        if (!indexFile.isFile || !namesFile.isFile) {
            Log.d(TAG, "POI side files absent (index=${indexFile.exists()} names=${namesFile.exists()})")
            return false
        }
        return try {
            val indexBuf = mapReadOnly(indexFile).also { it.order(ByteOrder.LITTLE_ENDIAN) }
            val namesBuf = mapReadOnly(namesFile)
            val count = (indexFile.length() / RECORD_BYTES).toInt()
            // Separate and optional: a failure here leaves the index perfectly
            // usable, just without attributes.
            val attrs = openAttrs(File(dir, ATTRS_FILE), count)
            mapped = Mapped(
                index = indexBuf,
                names = namesBuf,
                namesLen = namesBuf.capacity(),
                count = count,
                attrs = attrs?.first,
                attrsBlobStart = attrs?.second ?: 0,
            )
            Log.d(TAG, "Loaded $count POI records, names=${namesBuf.capacity()}B")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to map POI side files", e)
            mapped = null
            false
        }
    }

    /**
     * Map the attribute sidecar, returning null (rather than throwing) if anything is off.
     *
     * The record-count check is the one that matters: the sidecar joins to the
     * index purely by position, so a sidecar from a different build would hand
     * every place someone else's phone number. Refusing the file is the only safe
     * response, and there is no way to detect the mismatch later.
     *
     * @return the mapped buffer and the byte offset of the blob, or null.
     */
    private fun openAttrs(file: File, count: Int): Pair<MappedByteBuffer, Int>? {
        if (!file.isFile) {
            Log.d(TAG, "POI attribute sidecar absent")
            return null
        }
        try {
            val buf = mapReadOnly(file).also { it.order(ByteOrder.LITTLE_ENDIAN) }
            if (buf.capacity() < ATTRS_HEADER_BYTES) {
                Log.w(TAG, "$ATTRS_FILE is truncated")
                return null
            }
            for (i in ATTRS_MAGIC.indices) {
                if (buf.get(i) != ATTRS_MAGIC[i]) {
                    Log.w(TAG, "$ATTRS_FILE has the wrong magic")
                    return null
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
                return null
            }
            val blobStart = ATTRS_HEADER_BYTES + 4 * attrCount
            if (blobStart > buf.capacity()) {
                Log.w(TAG, "$ATTRS_FILE offset array runs past the file")
                return null
            }
            Log.d(TAG, "Loaded POI attributes for $attrCount record(s)")
            return buf to blobStart
        } catch (e: Exception) {
            Log.w(TAG, "Failed to map $ATTRS_FILE", e)
            return null
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

    /**
     * Case-insensitive prefix/substring search over the deduped name pool,
     * returning matching POIs ranked prefix-first then by distance to
     * ([nearLat],[nearLon]). Returns an empty list when nothing matches or the
     * index isn't loaded.
     */
    fun searchByName(
        query: String,
        nearLat: Double,
        nearLon: Double,
        limit: Int = 20,
    ): List<PoiRecord> {
        val m = mapped ?: return emptyList()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        // Pass 1: walk the name pool once, recording matching offsets and their
        // rank (0 = prefix match, 1 = substring). Decoding each unique name once
        // is the dedup win the side-file layout is designed for.
        val matchRank = HashMap<Int, Int>()
        var pos = 0
        while (pos < m.namesLen) {
            var end = pos
            while (end < m.namesLen && m.names.get(end).toInt() != 0) end++
            if (end > pos) {
                val name = m.nameAt(pos)
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
        val out = ArrayList<Ranked>(minOf(CANDIDATE_CAP, m.count))
        var i = 0
        while (i < m.count && out.size < CANDIDATE_CAP) {
            val off = m.nameOff(i)
            val rank = matchRank[off]
            if (rank != null) {
                val latE7 = m.latE7(i)
                val lonE7 = m.lonE7(i)
                out.add(
                    Ranked(
                        PoiRecord(latE7, lonE7, m.type(i), m.nameAt(off) ?: "", i),
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
    fun nearest(
        lat: Double,
        lon: Double,
        limit: Int = 20,
        maxMeters: Double = 250.0,
    ): List<PoiRecord> {
        val m = mapped ?: return emptyList()
        val dLat = maxMeters / 111_320.0
        val cosLat = Math.cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
        val dLon = maxMeters / (111_320.0 * cosLat)
        val minLatE7 = ((lat - dLat) * 1e7).toInt()
        val maxLatE7 = ((lat + dLat) * 1e7).toInt()
        val minLonE7 = ((lon - dLon) * 1e7).toInt()
        val maxLonE7 = ((lon + dLon) * 1e7).toInt()

        val hits = ArrayList<Hit>()
        m.forEachInBbox(minLatE7, maxLatE7, minLonE7, maxLonE7) { i, latE7, lonE7 ->
            hits.add(Hit(i, distanceSq(latE7 / 1e7, lonE7 / 1e7, lat, lon)))
            true
        }
        // Stable, so records at an equal distance stay in file order — the tie-break the
        // scan this replaced had, and what the equivalence test pins.
        hits.sortBy { it.distSq }
        return hits.take(limit).map { m.record(it.idx) }
    }

    /**
     * All POIs whose coordinate falls inside the [west]..[east] × [south]..[north]
     * bounding box, capped at [cap]. Names are decoded for each returned record.
     * Empty when the index isn't loaded; used to drive the ambient offline pin
     * overlay (P29).
     */
    fun inViewport(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        cap: Int = 300,
    ): List<PoiRecord> {
        val m = mapped ?: return emptyList()
        if (cap <= 0) return emptyList()
        val minLatE7 = (south * 1e7).toInt()
        val maxLatE7 = (north * 1e7).toInt()
        val minLonE7 = (west * 1e7).toInt()
        val maxLonE7 = (east * 1e7).toInt()
        val out = ArrayList<PoiRecord>(minOf(cap, m.count))
        m.forEachInBbox(minLatE7, maxLatE7, minLonE7, maxLonE7) { i, _, _ ->
            out.add(m.record(i))
            out.size < cap
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
    fun attributesAt(ordinal: Int): PoiAttributes? {
        val m = mapped ?: return null
        val buf = m.attrs ?: return null
        if (ordinal < 0 || ordinal >= m.count) return null
        val off = buf.getInt(ATTRS_HEADER_BYTES + 4 * ordinal)
        if (off == NO_ATTRS || off < 0) return null
        // Bounded before the add, not after: `attrsBlobStart + off` can wrap negative
        // for a large positive off, and a negative index passes an `at + 2 > capacity`
        // test on its way to an IndexOutOfBoundsException.
        val blobLen = buf.capacity() - m.attrsBlobStart
        if (off > blobLen - 2) return null
        val at = m.attrsBlobStart + off
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
     * Costs one [nearest] call, which is a binary search plus a walk of a 25 m box.
     * Still a file read against a cold mmap, so callers keep it off the main thread.
     */
    fun attributesNear(
        lat: Double,
        lon: Double,
        name: String,
        maxMeters: Double = 25.0,
    ): PoiAttributes? {
        if (mapped?.attrs == null || name.isBlank()) return null
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
