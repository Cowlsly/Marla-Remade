package com.vayunmathur.maps.util

import android.content.Context
import java.io.File

/**
 * Where the map archives live: the streamed protomaps ones, and the `.mamaps` basemap
 * that is now fetched once onto the device instead.
 *
 * Only the URLs and the on-disk location: the byte-range fetching and disk cache for the
 * streamed archives are inside `:library:map`'s renderer, which owns its own cache directory.
 */
object MapTileCache {
    /**
     * The `.mamaps` basemap, fetched once into external files alongside the graph and POI
     * packs rather than range-requested for the life of the install.
     *
     * Named for its role rather than its extent, so the same install can be handed a
     * state-sized archive today and a planet one later without the filename becoming a lie.
     */
    const val BASEMAP_ARCHIVE_FILE = "basemap.mamaps"

    /** Where [BASEMAP_ARCHIVE_FILE] is fetched from on a first launch that has no copy yet. */
    const val BASEMAP_ARCHIVE_URL =
        "https://data.vayunmathur.com/planet.mamaps"

    /**
     * The on-device archive, or null when there is no copy yet.
     *
     * A sideloaded archive is indistinguishable from a downloaded one, and that is the point:
     * `adb push`ing a locally built `.mamaps` here both satisfies the first-launch download
     * gate and becomes what the renderer opens, so iterating on the tiler does not mean
     * waiting on a multi-gigabyte transfer. Null sends the renderer back to streaming
     * [BASEMAP_ARCHIVE_URL] by range request.
     */
    fun localArchive(context: Context): String? =
        File(context.getExternalFilesDir(null), BASEMAP_ARCHIVE_FILE)
            .takeIf { it.isFile }
            ?.absolutePath

    /**
     * The single source of truth for the streamed basemap PMTiles URL.
     *
     * Base schema ONLY — the Protomaps layers. Our own overlays live in
     * [OVERLAY_PMTILES_URL], a separate archive, because at planet scale the base is
     * ~127 GB and the overlays are a couple of GB: joining them would make the merge
     * impossible for the sake of a file 97% of which never changes.
     * See `scripts/maps/build_v5_pmtiles.sh --no-base`.
     */
    const val BASEMAP_PMTILES_URL =
        "pmtiles://https://data.vayunmathur.com/v4.pmtiles"

    /**
     * Our overlay archive: `safety`, `roads`, `transit_lines`, `ma_pois`,
     * `transit_stops` and the three `admin_*` levels, with no base layers.
     *
     * Planet-wide: 36.8 M tiles over z0-16, built by
     * `build_v5_pmtiles.sh --no-base` and streamed by range request like the base.
     */
    const val OVERLAY_PMTILES_URL =
        "pmtiles://https://data.vayunmathur.com/v5-overlay.pmtiles"
}
