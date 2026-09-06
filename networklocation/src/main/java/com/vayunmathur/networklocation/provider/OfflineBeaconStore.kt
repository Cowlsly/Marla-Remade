package com.vayunmathur.networklocation.provider

import android.content.Context
import android.os.ParcelFileDescriptor
import com.vayunmathur.networklocation.BeaconFix
import com.vayunmathur.networklocation.BeaconId
import com.vayunmathur.networklocation.OfflineDatabases
import com.vayunmathur.networklocation.WpsStoreNative

/**
 * Offline beacon → coordinate resolver over the two WPSDB stores (`wifi.wpsdb`,
 * `cells.wpsdb`), read by [WpsStoreNative] (native Rust reader) from device-protected
 * storage — see [OfflineDatabases]. They are downloaded rather than bundled, the same as
 * the offline geocoder in `GeocodeService`.
 *
 * Degrades gracefully: if a store has not been downloaded or the native library did not
 * load, the corresponding handle stays 0 and every lookup misses, so the provider falls back
 * to pure-online behaviour and a DB-less dev build still works. A store that arrives later is
 * picked up on the next lookup ([reopenIfArrived]) rather than at the next process start.
 *
 * The 48-bit MAC packing and 64-bit cell-key packing here MUST stay byte-for-byte identical
 * to `wtfps-experiment/store.py` (`parse_mac` / `pack_cell`), which builds the stores — see
 * FORMAT.md "Cell key packing".
 */
class OfflineBeaconStore(context: Context) {
    private val appContext = context.applicationContext

    private var wifiPfd: ParcelFileDescriptor? = null
    private var cellPfd: ParcelFileDescriptor? = null
    private var wifiHandle = 0L
    private var cellHandle = 0L

    // Serializes lookups against close(): native close() frees the reader (Box::from_raw),
    // so it must not run while another thread is inside WpsStoreNative.lookup on that handle.
    private val lock = Any()

    init {
        open()
    }

    /**
     * (Re)open both stores. Call again once a download completes; existing handles are
     * released first, and a store that is still absent simply leaves its handle at 0.
     */
    fun open() {
        synchronized(lock) {
            closeLocked()
            if (!WpsStoreNative.available) return
            wifiHandle = openStore(OfflineDatabases.WIFI) { wifiPfd = it }
            cellHandle = openStore(OfflineDatabases.CELL) { cellPfd = it }
        }
    }

    private fun openStore(name: String, keep: (ParcelFileDescriptor) -> Unit): Long {
        val fd = OfflineDatabases.openReadOnly(appContext, name) ?: return 0L
        keep(fd)
        // Standalone file: the reader's base offset is 0, unlike the old APK-asset path.
        return WpsStoreNative.open(fd.fd, 0L, fd.statSize)
    }

    /**
     * Reopen both stores if either handle is still 0 and its file has since appeared.
     *
     * The stores are downloaded on demand and nothing signals completion, so without this a
     * provider bound at boot keeps missing until the process restarts. Costs one stat per
     * absent store on the lookup path.
     */
    private fun reopenIfArrived() {
        synchronized(lock) {
            val wifiArrived =
                wifiHandle == 0L && OfflineDatabases.isPresent(appContext, OfflineDatabases.WIFI)
            val cellArrived =
                cellHandle == 0L && OfflineDatabases.isPresent(appContext, OfflineDatabases.CELL)
            if (wifiArrived || cellArrived) open()
        }
    }

    /** Resolve WiFi APs present in the offline store. Absent MACs are simply omitted. */
    fun resolveWifi(bssids: List<BeaconId.Wifi>): Map<BeaconId.Wifi, BeaconFix> {
        if (bssids.isEmpty()) return emptyMap()
        reopenIfArrived()
        synchronized(lock) {
            if (wifiHandle == 0L) return emptyMap()
            val out = HashMap<BeaconId.Wifi, BeaconFix>()
            for (id in bssids) {
                val key = parseMac(id.bssid) ?: continue
                val r = WpsStoreNative.lookup(wifiHandle, key) ?: continue
                if (r.size >= 2) out[id] = BeaconFix(id, r[0], r[1], OFFLINE_ACCURACY_METERS)
            }
            return out
        }
    }

    /** Resolve cell towers present in the offline store. Absent towers are omitted. */
    fun resolveCell(cells: List<BeaconId.Cell>): Map<BeaconId.Cell, BeaconFix> {
        if (cells.isEmpty()) return emptyMap()
        reopenIfArrived()
        synchronized(lock) {
            if (cellHandle == 0L) return emptyMap()
            val out = HashMap<BeaconId.Cell, BeaconFix>()
            for (id in cells) {
                val r = WpsStoreNative.lookup(cellHandle, packCell(id)) ?: continue
                if (r.size >= 2) out[id] = BeaconFix(id, r[0], r[1], OFFLINE_ACCURACY_METERS)
            }
            return out
        }
    }

    /**
     * Release both handles and their descriptors. Idempotent. Serialized with the resolvers so
     * it never frees a native reader that a concurrent lookup is still dereferencing.
     */
    fun close() {
        synchronized(lock) {
            closeLocked()
        }
    }

    private fun closeLocked() {
        if (wifiHandle != 0L) {
            WpsStoreNative.close(wifiHandle)
            wifiHandle = 0L
        }
        if (cellHandle != 0L) {
            WpsStoreNative.close(cellHandle)
            cellHandle = 0L
        }
        wifiPfd?.close()
        wifiPfd = null
        cellPfd?.close()
        cellPfd = null
    }

    private companion object {
        // The stores quantize coordinates to a ~20 m global grid (see quantize.py); use a
        // fixed accuracy radius matching that precision for every offline fix.
        const val OFFLINE_ACCURACY_METERS = 20.0

        // Cell key packing — MUST match store.pack_cell:
        //   key = (mcc<<54) | (mnc<<44) | (tac<<28) | (cellId & 0x0FFFFFFF)
        //   mcc:10 | mnc:10 | tac:16 | cellId:28  (= 64 bits)
        // 5G NCI cell ids (36-bit) are truncated to 28 bits; LTE/UMTS/GSM fit.
        const val CID_MASK = 0x0FFF_FFFFL

        fun packCell(id: BeaconId.Cell): Long =
            ((id.mcc.toLong() and 0x3FF) shl 54) or
                ((id.mnc.toLong() and 0x3FF) shl 44) or
                ((id.tacOrLac.toLong() and 0xFFFF) shl 28) or
                (id.cellId.toLong() and CID_MASK)

        /** Parse "aa:bb:cc:dd:ee:ff" to a 48-bit key (mirrors store.parse_mac); null if bad. */
        fun parseMac(bssid: String): Long? {
            val parts = bssid.split(":")
            if (parts.size != 6) return null
            var v = 0L
            for (p in parts) {
                val b = p.toIntOrNull(16) ?: return null
                if (b < 0 || b > 0xFF) return null
                v = (v shl 8) or b.toLong()
            }
            return v
        }
    }
}
