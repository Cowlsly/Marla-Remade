package com.vayunmathur.library.map

/**
 * Which optional map layers to draw. Both are off by default.
 *
 * Every `.mamaps` archive already carries POI and transit data — the tiler has emitted both
 * for some time — but nothing drew either, so every archive paid storage, bandwidth and
 * decode cost for bytes that were discarded. Turning them on is opt-in rather than opt-out
 * because most consumers (findfamily, photos, weather, taxi, fooddelivery) show a map as a
 * backdrop for their own pins, and neither layer helps that: POI icons compete with the pin,
 * and rail lines are noise outside a transit app. `maps` opts into transit, driven by its
 * own layers toggle; `mapcompare` can drive both.
 *
 * # What turning one on costs
 *
 * Unlike the light/dark switch, this is not free. Both layers are gated at **tessellation**
 * time precisely so that leaving them off costs nothing — with [poi] off no label is shaped
 * and no collision candidate is built for any resident tile. The price is that flipping
 * either one invalidates the meshes already on the GPU, so the renderer re-tessellates the
 * resident tiles from the archive it already holds. No tile is refetched and none is
 * evicted; the existing meshes keep drawing until their replacements land, so the map does
 * not blank. Expect a brief burst of worker activity, not a reload.
 *
 * Setting the same values again is a no-op, so it is safe to pass a freshly-constructed
 * instance on every recomposition.
 */
data class LayerOptions(
    /**
     * Points of interest: an icon plus a wrapped label per feature, at z17 and deeper.
     *
     * Matches the reference basemap's `pois` layer — the same 36 kinds, the same sprite
     * sheet, the same six-way colour split by kind, and the same left/right anchor flip at
     * the screen edge. Density is governed by the archive, which drops each kind below its
     * own minimum zoom, so this stays empty at world zoom whatever the flag says.
     */
    val poi: Boolean = false,
    /**
     * Rail transit lines, coloured per route.
     *
     * Non-bus routes only. Each line takes the operator's own `colour=` tag from OSM, so
     * these are brand colours and are deliberately **not** shifted by the dark palette —
     * a line that is red on the network map stays red on a dark basemap.
     */
    val transit: Boolean = false,
) {
    companion object {
        /** The default: basemap only. */
        val None = LayerOptions()

        /** Both optional layers on. */
        val All = LayerOptions(poi = true, transit = true)
    }
}
