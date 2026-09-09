package com.vayunmathur.maps.util

/**
 * Where the streamed protomaps archives live.
 *
 * Only the URLs: the byte-range fetching and disk cache for them are inside
 * `:library:map`'s renderer, which owns its own cache directory.
 */
object MapTileCache {
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
