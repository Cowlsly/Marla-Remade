package com.vayunmathur.maps.ui.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.ui.FAMILY_LOCATION_LAYER_ID
import com.vayunmathur.maps.ui.MA_POIS_LAYER_ID
import com.vayunmathur.maps.ui.PARKING_PIN_LAYER_ID
import com.vayunmathur.maps.ui.SAVED_PLACE_LAYER_ID
import com.vayunmathur.maps.ui.SEARCH_RESULT_LAYER_ID
import com.vayunmathur.maps.ui.TRANSIT_STOP_LAYER_ID
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.ui.toSelectedFamilyMember
import com.vayunmathur.maps.ui.toSelectedMaPoi
import com.vayunmathur.maps.ui.toSelectedSavedPlace
import com.vayunmathur.maps.ui.toSelectedSearchResult
import com.vayunmathur.maps.ui.toTransitStop

/**
 * What a tap on the map resolved to.
 *
 * A closed set, because the caller has to do something different for each and the compiler
 * should say so when a new one appears.
 */
sealed interface MapHit {
    /** The saved parking spot: opens the parking sheet rather than selecting a place. */
    data object Parking : MapHit

    /** A baked GTFS stop: opens its departure board. */
    data class Stop(val stop: TransitStop) : MapHit

    /** A pin or label that resolves to a selectable place. */
    data class Place(val feature: SpecificFeature) : MapHit
}

/**
 * Where features can be queried from. One method, so the picker can be tested without a
 * renderer — the whole reason this is a class and not a lambda inside a gesture handler.
 */
fun interface FeatureSource {
    fun query(box: DpRect, layerIds: Set<String>): List<Feature1>
}

/**
 * Resolves a tap to the topmost thing under it.
 *
 * Hit-testing is a **priority order**, not a set of conditions: a parking pin beats a transit
 * stop beats a search result, and so on down to the basemap's own labels. Expressed as an
 * ordered list of probes resolved by `firstNotNullOfOrNull`, so the order is a value that can
 * be read and asserted on rather than the shape of a hundred-line chain of `?:` and early
 * `return`s.
 *
 * Not a composable and not suspending. It does no I/O — the admin-label branch used to make a
 * Wikidata round-trip inside the gesture handler; that now belongs to the ViewModel, which is
 * why [adminLabelIds] hits are returned as raw features for the caller to resolve.
 */
class MapFeaturePicker(
    private val source: FeatureSource,
    /** Whether the transit layer is on. Stops are only probed when they are drawn. */
    private val transitEnabled: Boolean,
) {

    /**
     * One layer to probe, and how to turn a hit on it into a [MapHit].
     *
     * [enabled] rather than filtering the list at construction, so a disabled probe still
     * appears in [probes] and the order stays legible.
     */
    private class Probe(
        val layerId: String,
        val enabled: Boolean = true,
        val resolve: (List<Feature1>) -> MapHit?,
    )

    private val probes: List<Probe> = listOf(
        Probe(PARKING_PIN_LAYER_ID) { hits -> if (hits.isNotEmpty()) MapHit.Parking else null },
        Probe(TRANSIT_STOP_LAYER_ID, enabled = transitEnabled) { hits ->
            hits.firstNotNullOfOrNull { it.toTransitStop() }?.let { MapHit.Stop(it) }
        },
        Probe(SEARCH_RESULT_LAYER_ID) { hits ->
            hits.firstNotNullOfOrNull { it.toSelectedSearchResult() }?.let { MapHit.Place(it) }
        },
        Probe(SAVED_PLACE_LAYER_ID) { hits ->
            hits.firstNotNullOfOrNull { it.toSelectedSavedPlace() }?.let { MapHit.Place(it) }
        },
        Probe(FAMILY_LOCATION_LAYER_ID) { hits ->
            hits.firstNotNullOfOrNull { it.toSelectedFamilyMember() }?.let { MapHit.Place(it) }
        },
        Probe(MA_POIS_LAYER_ID) { hits ->
            hits.firstNotNullOfOrNull { it.toSelectedMaPoi() }?.let { MapHit.Place(it) }
        },
    )

    /** The layers probed, in priority order. Disabled ones are omitted. */
    val probeOrder: List<String> get() = probes.filter { it.enabled }.map { it.layerId }

    /**
     * The topmost pin under [offset], or null when the tap landed on no pin.
     *
     * Every probe uses the same tolerance box so a tap NEAR a small glyph still counts; see
     * [MapChromeMetrics.hitSlop].
     */
    fun pickPin(offset: DpOffset): MapHit? {
        val box = hitBox(offset)
        return probes.firstNotNullOfOrNull { probe ->
            if (!probe.enabled) null else probe.resolve(source.query(box, setOf(probe.layerId)))
        }
    }

    /**
     * The basemap's own place labels under [offset], returned unresolved.
     *
     * Queried at the exact point rather than through the tolerance box, matching the behaviour
     * this replaced. `queryRenderedFeatures` returns one feature per layer, so this is a
     * handful of candidates for the caller to resolve.
     */
    fun pickAdminLabels(offset: DpOffset): List<Feature1> =
        source.query(DpRect(offset, DpSize.Zero), ADMIN_LABEL_LAYER_IDS)

    private fun hitBox(offset: DpOffset): DpRect {
        val pad = MapChromeMetrics.hitSlop
        return DpRect(offset.x - pad, offset.y - pad, offset.x + pad, offset.y + pad)
    }

    companion object {
        /**
         * The basemap label layers probed for a "what's here?" tap.
         *
         * A `Set`, so this does not express a priority: the renderer decides what order it
         * returns them in, and the caller resolves the first that parses. `_base`/`_hybrid`
         * because the style patcher splits every layer in two by zoom.
         */
        val ADMIN_LABEL_LAYER_IDS: Set<String> =
            setOf("places_country", "places_region", "places_locality")
                .flatMap { listOf("${it}_base", "${it}_hybrid") }
                .toSet()
    }
}
