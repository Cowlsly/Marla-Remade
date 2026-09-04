package com.vayunmathur.maps.util

import com.vayunmathur.library.map.GeoBounds
import com.vayunmathur.library.map.GeoPoint
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * Bridge between the app's [Position] (spatialk, used by routing / ViewModels /
 * offline indexes) and library:map's [GeoPoint].
 *
 * Kept in :maps so :library:map stays spatialk-free. Both are lon-first.
 */
fun Position.toGeoPoint(): GeoPoint = GeoPoint(longitude, latitude)

fun GeoPoint.toPosition(): Position = Position(longitude, latitude)

fun BoundingBox.toGeoBounds(): GeoBounds =
    GeoBounds(west = west, south = south, east = east, north = north)

fun GeoBounds.toBoundingBox(): BoundingBox =
    BoundingBox(west = west, south = south, east = east, north = north)
