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
 *  * `poi_attrs.bin` - OPTIONAL attribute sidecar (opening hours, phone,
 *    website, address, cuisine, wheelchair), indexed by the ORDINAL of the
 *    matching `poi_index.bin` record. See `scripts/maps/osm_ingest/src/poi_attrs.rs`
 *    for the layout.
 *  * `poi_spatial.bin` - OPTIONAL sparse CSR lat/lon grid over record ordinals, so
 *    a bbox query visits only the cells it overlaps.
 *  * `poi_name_index.bin` - OPTIONAL word index: one `(record, word)` entry for
 *    every word of every name, sorted by word, so name search is a binary search.
 *    Both are laid out in `scripts/maps/osm_ingest/src/poi_side.rs`.
 *
 * It exposes a name search and a spatial nearest lookup, so a POI query can resolve
 * locally without a Google call. Everything is a harmless no-op until the index and
 * the name pool are present (they ship from the same host as the graph); queries then
 * return empty lists. The three side files are each separately optional, and each is
 * refused rather than trusted when its record count disagrees with the index — they
 * all join by ordinal, so a stale one would return the wrong place rather than none.
 *
 * **Nothing here scans the dataset.** Every lookup used to walk all `count` records,
 * which was imperceptible over a California extract and ANR'd the app on a planet-wide
 * one - 22.6 M records on the main thread inside a tap handler. Now:
 *
 *  * bbox queries go through the grid, or fall back to a Morton-span walk of the
 *    already-sorted `poi_index.bin` — see [Mapped.forEachInBbox] and its caveat;
 *  * name search goes through the word index, or falls back to the two-pass scan.
 *    The two differ deliberately; [searchByName] says how.
 */
object PoiIndex {
    private const val TAG = "PoiIndex"
    const val INDEX_FILE = "poi_index.bin"
    const val NAMES_FILE = "poi_names.bin"
    const val ATTRS_FILE = "poi_attrs.bin"
    const val SPATIAL_FILE = "poi_spatial.bin"
    const val NAME_INDEX_FILE = "poi_name_index.bin"

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

    // --- poi_spatial.bin / poi_name_index.bin (see osm_ingest/src/poi_side.rs) -----
    private val SPATIAL_MAGIC =
        byteArrayOf('P'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
    private const val SPATIAL_VERSION = 1
    private const val SPATIAL_HEADER_BYTES = 32

    private val NAME_INDEX_MAGIC =
        byteArrayOf('P'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), '1'.code.toByte())
    private const val NAME_INDEX_VERSION = 1
    private const val NAME_INDEX_HEADER_BYTES = 16

    /**
     * ASCII-only lowercase, and deliberately not [Char.lowercase].
     *
     * `poi_name_index.bin` is sorted by the writer, so the reader has to reproduce that
     * order byte for byte. Rust's `to_lowercase` and Kotlin's `lowercase` do not agree on
     * every input, and here a disagreement is a POI that can never be found. See the
     * cross-language contract in `osm_ingest/src/poi_side.rs`.
     */
    private fun asciiLower(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return if (v >= 'A'.code && v <= 'Z'.code) v + 32 else v
    }

    private fun isAsciiSpace(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v == ' '.code || v == '\t'.code || v == '\n'.code || v == '\r'.code ||
            v == 0x0B || v == 0x0C
    }

    /** A query as the index's sort key: UTF-8 bytes, ASCII-lowercased. */
    private fun queryKey(query: String): ByteArray {
        val raw = query.toByteArray(Charsets.UTF_8)
        return ByteArray(raw.size) { asciiLower(raw[it]).toByte() }
    }

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
        /** The CSR spatial grid, or null when absent or mismatched. */
        val spatial: MappedByteBuffer? = null,
        val cellCount: Int = 0,
        val lat0E7: Int = 0,
        val lon0E7: Int = 0,
        val cellE7: Int = 0,
        val cols: Int = 0,
        /** The word index, or null when absent or mismatched. */
        val nameIdx: MappedByteBuffer? = null,
        val entryCount: Int = 0,
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
         * Visit every record inside the bbox in **ordinal order**, stopping early when
         * [onHit] returns false.
         *
         * Two implementations, picked by whether `poi_spatial.bin` was mapped:
         *
         *  * **Grid** — only the cells the box actually overlaps are visited, so the cost
         *    is the box's own area. Exact, and with no pathological cases.
         *  * **Morton walk** — a binary search to the box's lower key bound, then a walk to
         *    its upper bound. The bound is the box's *Morton span*, which is not the box: a
         *    box straddling the equator or the prime meridian flips a top-level Z-curve bit
         *    and covers most of the key space, degenerating back toward a full walk. Kept
         *    because the side file is optional, and it still beats scanning [count].
         *
         * Ordinal order either way. The grid visits cells row by row, so its hits come out
         * shuffled relative to the file and are sorted before being yielded — callers that
         * truncate at a cap, or that break distance ties by file order, must not see a
         * different answer depending on which side files happen to be present.
         */
        fun forEachInBbox(
            minLatE7: Int,
            maxLatE7: Int,
            minLonE7: Int,
            maxLonE7: Int,
            onHit: (ordinal: Int, latE7: Int, lonE7: Int) -> Boolean,
        ) {
            if (minLatE7 > maxLatE7 || minLonE7 > maxLonE7) return
            if (spatial != null && cellCount > 0 && cols > 0 && cellE7 > 0) {
                forEachInCells(minLatE7, maxLatE7, minLonE7, maxLonE7, onHit)
            } else {
                forEachInMortonSpan(minLatE7, maxLatE7, minLonE7, maxLonE7, onHit)
            }
        }

        private fun forEachInMortonSpan(
            minLatE7: Int,
            maxLatE7: Int,
            minLonE7: Int,
            maxLonE7: Int,
            onHit: (ordinal: Int, latE7: Int, lonE7: Int) -> Boolean,
        ) {
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

        /** Cell offset along one axis. Must match `cell_axis` in `poi_side.rs`. */
        private fun axis(value: Int, origin: Int): Int {
            val d = value.toLong() - origin.toLong()
            return if (d <= 0) 0 else (d / cellE7).toInt()
        }

        private fun row(latE7: Int): Int = axis(latE7, lat0E7)
        private fun col(lonE7: Int): Int = axis(lonE7, lon0E7).coerceAtMost(cols - 1)

        /** Index of [cellId] in the ascending cell-id array, or -1 when unpopulated. */
        private fun cellIndexOf(cellId: Int): Int {
            val buf = spatial ?: return -1
            var lo = 0
            var hi = cellCount
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val v = buf.getInt(SPATIAL_HEADER_BYTES + 4 * mid)
                if (v == cellId) return mid
                if (v < cellId) lo = mid + 1 else hi = mid
            }
            return -1
        }

        /** CSR prefix entry [i], i.e. where cell `i`'s ordinals begin. */
        private fun cellOff(i: Int): Int =
            spatial!!.getInt(SPATIAL_HEADER_BYTES + 4 * cellCount + 4 * i)

        private fun gridOrdinal(k: Int): Int =
            spatial!!.getInt(SPATIAL_HEADER_BYTES + 4 * cellCount + 4 * (cellCount + 1) + 4 * k)

        private fun forEachInCells(
            minLatE7: Int,
            maxLatE7: Int,
            minLonE7: Int,
            maxLonE7: Int,
            onHit: (ordinal: Int, latE7: Int, lonE7: Int) -> Boolean,
        ) {
            val hits = ArrayList<Int>()
            for (r in row(minLatE7)..row(maxLatE7)) {
                for (c in col(minLonE7)..col(maxLonE7)) {
                    val ci = cellIndexOf(r * cols + c)
                    if (ci < 0) continue
                    for (k in cellOff(ci) until cellOff(ci + 1)) {
                        val ordinal = gridOrdinal(k)
                        if (ordinal < 0 || ordinal >= count) continue
                        if (latE7(ordinal) in minLatE7..maxLatE7 &&
                            lonE7(ordinal) in minLonE7..maxLonE7
                        ) {
                            hits.add(ordinal)
                        }
                    }
                }
            }
            hits.sort()
            for (ordinal in hits) {
                if (!onHit(ordinal, latE7(ordinal), lonE7(ordinal))) return
            }
        }

        // --- Word index --------------------------------------------------------

        private fun entryOrdinal(i: Int): Int =
            nameIdx!!.getInt(NAME_INDEX_HEADER_BYTES + 4 * i)

        private fun entryWordIdx(i: Int): Int =
            nameIdx!!.get(NAME_INDEX_HEADER_BYTES + 4 * entryCount + i).toInt() and 0xFF

        /**
         * Byte range of the [wordIdx]th whitespace-separated word of the name at [off], or
         * null when the name has fewer words. Must match `word_at` in `poi_side.rs`.
         */
        private fun wordRange(off: Int, wordIdx: Int): IntRange? {
            if (off < 0 || off >= namesLen) return null
            var i = off
            var idx = 0
            while (i < namesLen && names.get(i).toInt() != 0) {
                while (i < namesLen && names.get(i).toInt() != 0 && isAsciiSpace(names.get(i))) i++
                if (i >= namesLen || names.get(i).toInt() == 0) break
                val start = i
                while (i < namesLen && names.get(i).toInt() != 0 && !isAsciiSpace(names.get(i))) i++
                if (idx == wordIdx) return start until i
                idx++
            }
            return null
        }

        private fun entryWord(i: Int): IntRange? =
            wordRange(nameOff(entryOrdinal(i)), entryWordIdx(i))

        /** ASCII-lowercased byte compare of the word at [range] against [key]. */
        private fun compareWord(range: IntRange, key: ByteArray): Int {
            val len = range.last - range.first + 1
            for (k in 0 until minOf(len, key.size)) {
                val d = asciiLower(names.get(range.first + k)) - (key[k].toInt() and 0xFF)
                if (d != 0) return d
            }
            return len - key.size
        }

        private fun wordStartsWith(range: IntRange, key: ByteArray): Boolean {
            if (range.last - range.first + 1 < key.size) return false
            for (k in key.indices) {
                if (asciiLower(names.get(range.first + k)) != (key[k].toInt() and 0xFF)) return false
            }
            return true
        }

        /** First entry whose word is >= [key], or [entryCount]. */
        private fun lowerBoundWord(key: ByteArray): Int {
            var lo = 0
            var hi = entryCount
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val w = entryWord(mid)
                // A word we cannot resolve sorts first, so the search steps past it rather
                // than stalling on a name the index disagrees with.
                if (w == null || compareWord(w, key) < 0) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /**
         * Ordinals whose name has a word starting with [key], each paired with whether the
         * match was on the name's *first* word.
         *
         * Binary search plus a walk of the matching range, so the cost is the number of
         * matches rather than the size of the name pool.
         */
        fun wordPrefixMatches(key: ByteArray, cap: Int): List<IntArray> {
            if (nameIdx == null || entryCount == 0) return emptyList()
            val out = ArrayList<IntArray>()
            val seen = HashSet<Int>()
            var i = lowerBoundWord(key)
            while (i < entryCount && out.size < cap) {
                val w = entryWord(i) ?: break
                if (!wordStartsWith(w, key)) break
                val ordinal = entryOrdinal(i)
                // A name can match on more than one word ("Pizza Pizza"); the better rank
                // wins, and the index lists word 0 first within one name.
                if (seen.add(ordinal)) out.add(intArrayOf(ordinal, entryWordIdx(i)))
                i++
            }
            return out
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
            val grid = openSpatial(File(dir, SPATIAL_FILE), count)
            val words = openNameIndex(File(dir, NAME_INDEX_FILE), count)
            mapped = Mapped(
                index = indexBuf,
                names = namesBuf,
                namesLen = namesBuf.capacity(),
                count = count,
                attrs = attrs?.first,
                attrsBlobStart = attrs?.second ?: 0,
                spatial = grid?.buf,
                cellCount = grid?.cellCount ?: 0,
                lat0E7 = grid?.lat0E7 ?: 0,
                lon0E7 = grid?.lon0E7 ?: 0,
                cellE7 = grid?.cellE7 ?: 0,
                cols = grid?.cols ?: 0,
                nameIdx = words?.first,
                entryCount = words?.second ?: 0,
            )
            Log.d(
                TAG,
                "Loaded $count POI records, names=${namesBuf.capacity()}B, " +
                    "grid=${grid?.cellCount ?: 0} cells, words=${words?.second ?: 0}",
            )
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

    /** The mapped spatial grid plus the parameters read out of its header. */
    private class Grid(
        val buf: MappedByteBuffer,
        val cellCount: Int,
        val lat0E7: Int,
        val lon0E7: Int,
        val cellE7: Int,
        val cols: Int,
    )

    /**
     * Map `poi_spatial.bin`, or null when it is absent, stale or malformed.
     *
     * The record-count check matters for the same reason it does for the sidecar: the grid
     * stores *ordinals*, so a grid built against a different `poi_index.bin` would return
     * the wrong places rather than none. Refusing it costs only the Morton fallback.
     */
    private fun openSpatial(file: File, count: Int): Grid? {
        if (!file.isFile) return null
        return try {
            val buf = mapReadOnly(file).also { it.order(ByteOrder.LITTLE_ENDIAN) }
            if (buf.capacity() < SPATIAL_HEADER_BYTES) return null
            for (i in SPATIAL_MAGIC.indices) {
                if (buf.get(i) != SPATIAL_MAGIC[i]) {
                    Log.w(TAG, "$SPATIAL_FILE has the wrong magic; ignoring it")
                    return null
                }
            }
            if (buf.getInt(4) != SPATIAL_VERSION) {
                Log.w(TAG, "$SPATIAL_FILE version ${buf.getInt(4)} unsupported; ignoring it")
                return null
            }
            val records = buf.getInt(8)
            if (records != count) {
                Log.w(TAG, "$SPATIAL_FILE covers $records record(s), index has $count; ignoring it")
                return null
            }
            val cellCount = buf.getInt(12)
            val cols = buf.getInt(28)
            // cell_ids + cell_off + ordinals must all be present before any of them is read.
            val need = SPATIAL_HEADER_BYTES + 4L * cellCount + 4L * (cellCount + 1) + 4L * count
            if (cellCount < 0 || cols < 0 || need > buf.capacity()) {
                Log.w(TAG, "$SPATIAL_FILE is truncated; ignoring it")
                return null
            }
            Grid(buf, cellCount, buf.getInt(16), buf.getInt(20), buf.getInt(24), cols)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to map $SPATIAL_FILE", e)
            null
        }
    }

    /** Map `poi_name_index.bin` and its entry count, or null when unusable. */
    private fun openNameIndex(file: File, count: Int): Pair<MappedByteBuffer, Int>? {
        if (!file.isFile) return null
        return try {
            val buf = mapReadOnly(file).also { it.order(ByteOrder.LITTLE_ENDIAN) }
            if (buf.capacity() < NAME_INDEX_HEADER_BYTES) return null
            for (i in NAME_INDEX_MAGIC.indices) {
                if (buf.get(i) != NAME_INDEX_MAGIC[i]) {
                    Log.w(TAG, "$NAME_INDEX_FILE has the wrong magic; ignoring it")
                    return null
                }
            }
            if (buf.getInt(4) != NAME_INDEX_VERSION) {
                Log.w(TAG, "$NAME_INDEX_FILE version ${buf.getInt(4)} unsupported; ignoring it")
                return null
            }
            val records = buf.getInt(8)
            if (records != count) {
                Log.w(
                    TAG,
                    "$NAME_INDEX_FILE covers $records record(s), index has $count; ignoring it",
                )
                return null
            }
            val entries = buf.getInt(12)
            if (entries < 0 ||
                NAME_INDEX_HEADER_BYTES + 5L * entries > buf.capacity()
            ) {
                Log.w(TAG, "$NAME_INDEX_FILE is truncated; ignoring it")
                return null
            }
            buf to entries
        } catch (e: Exception) {
            Log.w(TAG, "Failed to map $NAME_INDEX_FILE", e)
            null
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
     * Name search, ranked first-word-first then by distance to ([nearLat],[nearLon]).
     *
     * Two implementations, picked by whether `poi_name_index.bin` was mapped:
     *
     *  * **Indexed** — binary search to the query's place in a word-sorted index, then a
     *    walk of the matching range, so the cost is the number of matches. Because every
     *    word of a name is indexed, "pizza" still finds "Joe's Pizza".
     *  * **Scan** — the name pool, then every record. Two passes whose cost is the whole
     *    dataset, kept only because the side file is optional.
     *
     * **The two do not match exactly, by design.** The scan matches a substring anywhere,
     * so "izza" finds "Pizza"; a sorted index can only answer prefix questions, so the
     * indexed path matches a prefix *of any word*. That is a deliberate narrowing —
     * mid-word matches are lost, whole-word ones are not.
     */
    fun searchByName(
        query: String,
        nearLat: Double,
        nearLon: Double,
        limit: Int = 20,
    ): List<PoiRecord> {
        val m = mapped ?: return emptyList()
        if (query.isBlank()) return emptyList()
        if (m.nameIdx != null) return searchByWordIndex(m, query, nearLat, nearLon, limit)
        return searchByScan(m, query, nearLat, nearLon, limit)
    }

    private fun searchByWordIndex(
        m: Mapped,
        query: String,
        nearLat: Double,
        nearLon: Double,
        limit: Int,
    ): List<PoiRecord> {
        val key = queryKey(query.trim())
        if (key.isEmpty()) return emptyList()
        val out = ArrayList<Ranked>()
        for (hit in m.wordPrefixMatches(key, CANDIDATE_CAP)) {
            val (ordinal, wordIdx) = hit
            // Matching the name's first word is the indexed equivalent of the scan's
            // "starts with", and ranks the same way.
            val rank = if (wordIdx == 0) 0 else 1
            out.add(
                Ranked(
                    m.record(ordinal),
                    rank,
                    distanceSq(nearLat, nearLon, m.latE7(ordinal) / 1e7, m.lonE7(ordinal) / 1e7),
                )
            )
        }
        out.sortWith(compareBy({ it.rank }, { it.distSq }))
        return out.take(limit).map { it.record }
    }

    private fun searchByScan(
        m: Mapped,
        query: String,
        nearLat: Double,
        nearLon: Double,
        limit: Int,
    ): List<PoiRecord> {
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
