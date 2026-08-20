package com.vayunmathur.maps.data.transit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MOTIS encodes each leg's real path as an OTP/Google polyline at **precision 7**,
 * not the 5 the format is usually seen with. Getting that wrong does not fail
 * loudly — it silently draws every route 100x away from where it belongs — so the
 * decoder is pinned here against a payload captured from `api.transitous.org`.
 */
class TransitousPolylineTest {

    /**
     * The first leg of a real 2026 `GET /api/v1/plan` response for San Francisco →
     * Oakland, verbatim. `precision` was 7 and `length` was 29. Note the `??`
     * runs: those are zero deltas, i.e. repeated points, which a decoder must
     * carry through rather than choke on.
     */
    private val sfLeg =
        "sz}noUrsz}}gAgIqH??q[ed@??_i@gv@??oRsW??eKmM??iIqK??iM}T??yJuO??kKoW??u[ei@mm@my@coBuuB??cV{[??wK{N??hMmRrJaN"

    @Test
    fun `decodes a captured MOTIS leg at precision 7`() {
        val points = TransitousDataSource.decodePolyline(sfLeg, precision = 7)
        assertEquals(29, points.size, "the response declared length 29")
        // The leg starts at the requested origin in San Francisco.
        val first = points.first()
        assertTrue(
            abs(first.latitude - 37.7749) < 0.01 && abs(first.longitude - -122.4194) < 0.01,
            "first point should be the SF origin, was $first",
        )
        // Every point stays in the Bay Area, which is the assertion that actually
        // catches a precision mix-up.
        for (p in points) {
            assertTrue(
                p.latitude in 37.0..38.5 && p.longitude in -123.0..-121.5,
                "point outside the Bay Area: $p",
            )
        }
    }

    @Test
    fun `decoding at the wrong precision moves the route off the planet`() {
        // Guards the reason `precision` is read from the response instead of being
        // a constant: precision 5 on precision-7 data is off by 100x.
        val wrong = TransitousDataSource.decodePolyline(sfLeg, precision = 5).first()
        assertTrue(
            abs(wrong.latitude) > 90.0,
            "precision 5 should produce a nonsense latitude, got $wrong",
        )
    }

    @Test
    fun `a whole degree delta does not overflow`() {
        // At precision 7 the zigzagged longitude delta of the first point reaches
        // ~2.4e9, past Int.MAX_VALUE. Accumulating in an Int wraps it into a
        // plausible-looking coordinate on the wrong side of the planet instead of
        // failing, so pin the real longitude.
        val first = TransitousDataSource.decodePolyline(sfLeg, precision = 7).first()
        assertEquals(-122.4193866, first.longitude, 1e-7)
        assertEquals(37.7748922, first.latitude, 1e-7)
    }

    @Test
    fun `decodes a known two point line`() {
        // The canonical Google example, at its native precision 5.
        val points = TransitousDataSource.decodePolyline("_p~iF~ps|U_ulLnnqC", precision = 5)
        assertEquals(2, points.size)
        assertEquals(38.5, points[0].latitude, 1e-6)
        assertEquals(-120.2, points[0].longitude, 1e-6)
        assertEquals(40.7, points[1].latitude, 1e-6)
        assertEquals(-120.95, points[1].longitude, 1e-6)
    }

    @Test
    fun `an empty or truncated polyline yields no crash`() {
        assertTrue(TransitousDataSource.decodePolyline("", precision = 7).isEmpty())
        // A lone latitude delta with no matching longitude is dropped.
        assertTrue(TransitousDataSource.decodePolyline("_p~iF", precision = 5).isEmpty())
    }
}
