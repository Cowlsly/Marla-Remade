package com.vayunmathur.maps.data.google

/**
 * A Street View panorama's metadata, resolved from a lat/lng by the keyless
 * `GeoPhotoService.SingleImageSearch` endpoint (the same one Google's own JS Maps
 * API uses — no API key, authorised by referer). Everything the in-app viewer
 * needs to fetch tiles, walk to neighbours, and attribute the imagery; the tiles
 * themselves come from `streetviewpixels-pa.googleapis.com/v1/tile` (also keyless).
 *
 * The equirectangular image is a 2:1 pyramid: at zoom `z` a modern capture is
 * `512·2^z` wide by `256·2^z` tall, cut into [tileSize]² tiles — but the shape is
 * NOT fixed (pre-2016 captures are `416·2^z`), so [levelDims] carries the pano's
 * own per-level dimensions and the tile loader sizes its grid from THOSE.
 *
 * Ported from Vela's `StreetViewPano` (model only; the renderer is the photos-app
 * pan/zoom viewer, not Vela's GLES sphere).
 */
data class StreetViewPano(
    val panoId: String,
    val lat: Double,
    val lng: Double,
    // Capture heading (degrees, true north): the compass direction at the image
    // centre. Kept for attribution/orientation; the flat pan/zoom viewer doesn't
    // reproject by it.
    val headingDeg: Double = 0.0,
    val tileSize: Int = 512,
    // Number of pyramid levels; a full-res equirect is ~400 MB decoded, so the
    // loader picks a level well below the max.
    val maxZoom: Int = 5,
    // Per-level equirect dimensions (width, height), zoom 0 upward, straight from
    // the metadata. Empty → assume the modern 512·2^z shape.
    val levelDims: List<Pair<Int, Int>> = emptyList(),
    // Attribution: the place label Google attaches to the pano, and the copyright.
    val addressLabel: String? = null,
    val copyright: String? = null,
    // Capture date of THIS pano (year, month). Google shows "Image capture: …".
    val captureYear: Int? = null,
    val captureMonth: Int? = null,
    // Walkable neighbours — the panoramas you can step to, one per rough direction.
    // Already de-cluttered from the raw ~100-pano local graph.
    val neighbors: List<StreetViewLink> = emptyList(),
)

/** A neighbouring pano you can walk to: its id, position, and the bearing+distance
 *  from the current pano (so the viewer can label a directional step). */
data class StreetViewLink(
    val panoId: String,
    val lat: Double,
    val lng: Double,
    val bearingDeg: Double,
    val distanceM: Double,
)
