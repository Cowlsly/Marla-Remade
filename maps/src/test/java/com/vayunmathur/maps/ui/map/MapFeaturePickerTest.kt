package com.vayunmathur.maps.ui.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.ui.FAMILY_LOCATION_LAYER_ID
import com.vayunmathur.maps.ui.MA_POIS_LAYER_ID
import com.vayunmathur.maps.ui.PARKING_PIN_LAYER_ID
import com.vayunmathur.maps.ui.SAVED_PLACE_LAYER_ID
import com.vayunmathur.maps.ui.SEARCH_RESULT_LAYER_ID
import com.vayunmathur.maps.ui.TRANSIT_STOP_LAYER_ID
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import kotlinx.coroutines.runBlocking
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hit-test priority, which used to be a hundred-line lambda inside a gesture handler and so
 * could not be tested at all. It is priority order that matters here: a parking pin and a POI
 * can sit on the same point, and which one a tap selects is a product decision, not an
 * accident of which `?:` came first.
 *
 * [FeatureSource] is the seam — no renderer, no composition, no device.
 *
 * [MapFeaturePicker.pickPin] suspends because the POI probe reads a sidecar file, so the tests
 * that call it run under [runBlocking]. Nothing here actually blocks: [FakeSource] answers from
 * a map, and no probe reaches real I/O.
 */
class MapFeaturePickerTest {

    /** Records what was asked for, and answers with whatever the test planted. */
    private class FakeSource(private val hits: Map<String, List<Feature1>>) : FeatureSource {
        val queried = mutableListOf<Set<String>>()
        val boxes = mutableListOf<DpRect>()

        override fun query(box: DpRect, layerIds: Set<String>): List<Feature1> {
            queried += layerIds
            boxes += box
            return layerIds.flatMap { hits[it] ?: emptyList() }
        }
    }

    /** A geometry-only feature: present on the layer, but with no properties to resolve from. */
    private fun bareFeature() = Feature1(Point(Position(0.0, 0.0)), null)

    private val tap = DpOffset(100.dp, 200.dp)

    @Test
    fun `probes run parking, stop, search, saved, family, poi in that order`() = runBlocking {
        val source = FakeSource(emptyMap())
        MapFeaturePicker(source, transitEnabled = true).pickPin(tap)

        assertEquals(
            listOf(
                setOf(PARKING_PIN_LAYER_ID),
                setOf(TRANSIT_STOP_LAYER_ID),
                setOf(SEARCH_RESULT_LAYER_ID),
                setOf(SAVED_PLACE_LAYER_ID),
                setOf(FAMILY_LOCATION_LAYER_ID),
                setOf(MA_POIS_LAYER_ID),
            ),
            source.queried,
        )
    }

    @Test
    fun `probeOrder matches what actually gets queried`() = runBlocking {
        val source = FakeSource(emptyMap())
        val picker = MapFeaturePicker(source, transitEnabled = true)
        picker.pickPin(tap)
        assertEquals(picker.probeOrder, source.queried.map { it.single() })
    }

    /** Parking wins outright: a car spot on top of a POI is still the car spot. */
    @Test
    fun `parking beats everything below it and stops the search`() = runBlocking {
        val source = FakeSource(
            mapOf(
                PARKING_PIN_LAYER_ID to listOf(bareFeature()),
                MA_POIS_LAYER_ID to listOf(bareFeature()),
            )
        )
        val hit = MapFeaturePicker(source, transitEnabled = true).pickPin(tap)

        assertEquals(MapHit.Parking, hit)
        assertEquals(1, source.queried.size, "later probes must not run once a hit is found")
    }

    /** The transit probe is skipped entirely when the layer is not drawn. */
    @Test
    fun `a transit stop is not probed while the layer is off`() = runBlocking {
        val source = FakeSource(emptyMap())
        MapFeaturePicker(source, transitEnabled = false).pickPin(tap)
        assertTrue(
            source.queried.none { TRANSIT_STOP_LAYER_ID in it },
            "probed an invisible layer: ${source.queried}",
        )
    }

    @Test
    fun `probeOrder omits the transit layer while it is off`() {
        val picker = MapFeaturePicker(FakeSource(emptyMap()), transitEnabled = false)
        assertTrue(TRANSIT_STOP_LAYER_ID !in picker.probeOrder)
        assertEquals(5, picker.probeOrder.size)
    }

    /**
     * A feature that is present but does not resolve must not stop the search — the layer
     * being hit and the hit being usable are different things.
     */
    @Test
    fun `an unresolvable feature falls through to the next probe`() = runBlocking {
        val source = FakeSource(mapOf(SEARCH_RESULT_LAYER_ID to listOf(bareFeature())))
        val hit = MapFeaturePicker(source, transitEnabled = false).pickPin(tap)

        assertNull(hit)
        assertTrue(
            source.queried.any { MA_POIS_LAYER_ID in it },
            "gave up before reaching the POI layer",
        )
    }

    @Test
    fun `no hits anywhere yields null`() = runBlocking {
        assertNull(MapFeaturePicker(FakeSource(emptyMap()), transitEnabled = true).pickPin(tap))
    }

    /** The slop box is what makes tapping *near* a small glyph work. */
    @Test
    fun `every pin probe uses the same hit slop box around the tap`() = runBlocking {
        val source = FakeSource(emptyMap())
        MapFeaturePicker(source, transitEnabled = true).pickPin(tap)

        val pad = MapChromeMetrics.hitSlop
        val expected = DpRect(tap.x - pad, tap.y - pad, tap.x + pad, tap.y + pad)
        assertTrue(source.boxes.isNotEmpty())
        for (box in source.boxes) assertEquals(expected, box)
    }

    /** Labels are probed at the point, with no slop — matching the pre-refactor behaviour. */
    @Test
    fun `admin labels are probed at the exact point`() {
        val source = FakeSource(emptyMap())
        MapFeaturePicker(source, transitEnabled = true).pickAdminLabels(tap)

        assertEquals(1, source.boxes.size)
        assertEquals(DpRect(tap.x, tap.y, tap.x, tap.y), source.boxes.single())
        assertEquals(MapFeaturePicker.ADMIN_LABEL_LAYER_IDS, source.queried.single())
    }

    /** The style patcher splits every base layer into `_base` and `_hybrid` by zoom. */
    @Test
    fun `admin label ids cover both zoom variants of all three levels`() {
        val ids = MapFeaturePicker.ADMIN_LABEL_LAYER_IDS
        assertEquals(6, ids.size)
        for (level in listOf("places_country", "places_region", "places_locality")) {
            assertTrue("${level}_base" in ids, "missing ${level}_base")
            assertTrue("${level}_hybrid" in ids, "missing ${level}_hybrid")
        }
    }
}
