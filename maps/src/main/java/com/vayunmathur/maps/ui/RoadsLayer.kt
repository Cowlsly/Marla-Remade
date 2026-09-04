package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable

/**
 * The drawn road surface — currently a NO-OP on the phone map (renderer gap, see below).
 * Kept (rather than deleted) so [RoadsSource]'s schema constants and the layer's
 * documented contract survive the swap untouched.
 *
 * GAP (reported to lead; symbol-renderer owns the renderer API): roads used to render from
 * the baked `roads` source-layer in the overlay PMTiles archive (class/lanes/width/speed
 * attributes, casing + surface + an invisible posted-limit probe), and library:map has no
 * vector-layer API — so there is nothing to draw them with. The Vulkan basemap still draws
 * its own road network from `basemap.flat.json`; what is lost is the overlay's lane/width
 * styling and the posted-limit probe (`queryPostedLimit` was removed with it — the speed
 * badge now shows without a limit until renderer query support lands).
 */
object RoadsSource {
    const val SOURCE_LAYER: String = "roads"

    /** The OSM tag verbatim. `maxspeed_kmh` beside it is the parsed number, when there is one. */
    const val PROP_MAXSPEED: String = "maxspeed"

    /** `tags::get_hw_id`'s road type, 1-15. Also the routing graph's own numbering. */
    const val PROP_CLASS: String = "class"
}

/** Draw the `roads` source-layer. No-op until the renderer can draw vector source-layers. */
@Composable
fun RoadsLayer() {
    // Intentionally empty (see RoadsSource KDoc).
}
