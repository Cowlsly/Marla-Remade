package com.vayunmathur.maps.data.transit

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.maps.util.RouteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.geojson.Position
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

/**
 * Online public-transit data source (P10) backed by **Transitous** — a free,
 * community-hosted aggregation of open GTFS / GTFS-RT feeds — via its **MOTIS**
 * REST API. Two calls are exposed:
 *
 *  - [stopsInBbox] — nearby stops for the current viewport (`/api/v1/map/stops`,
 *    the same endpoint the MOTIS web map uses to draw stop dots), for the
 *    overlay layer.
 *  - [departures] — the live board for one stop (`/api/v1/stoptimes`): route
 *    short name, headsign, scheduled + realtime time, delay, platform.
 *
 * Design mirrors [com.vayunmathur.maps.data.google.GooglePoiDiscovery]:
 *  - an `object` singleton, all network on [Dispatchers.IO];
 *  - **never throws** — every public call returns empty on any failure so a
 *    flaky feed or a MOTIS schema drift degrades gracefully;
 *  - **brief caching**: stops are cached per rounded viewport (they rarely
 *    change); departures are cached per stop for only [DEPARTURES_TTL_MS] since
 *    they are live.
 *
 * ONLINE-ONLY (P11 adds offline transit from bundled GTFS). Routing is
 * untouched — this is a read-only board.
 *
 * NOTE (on-device): the live MOTIS fetch needs a device/emulator with network
 * and cannot be exercised at compile time; the endpoints/parse below are
 * verified by shape only.
 */
object TransitousDataSource {

    private const val BASE_URL = "https://api.transitous.org"

    /** Cap departures requested per board so a busy hub stays scrollable. */
    private const val DEPARTURE_COUNT = 30

    /** Cap stops per viewport so a dense metro area doesn't flood the overlay. */
    private const val MAX_STOPS = 200

    /** Departures are live — cache only very briefly to smooth refresh taps. */
    private const val DEPARTURES_TTL_MS = 20_000L

    /**
     * How long a *failed* fetch is remembered. Distinct from (and far shorter
     * than) the success TTLs: one offline attempt used to poison the departures
     * cache for the full 20 s and the stops cache indefinitely.
     */
    private const val FAILURE_TTL_MS = 3_000L

    private val REQUEST_HEADERS = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Modern-Apps-Maps/1.0",
    )

    /**
     * A cached fetch. [ok] separates "successfully fetched, genuinely empty"
     * from "the fetch failed", so only the latter expires quickly.
     */
    private class Cached<T>(val value: T, val ok: Boolean) {
        val at: Long = System.currentTimeMillis()
        fun isFresh(okTtlMs: Long): Boolean =
            System.currentTimeMillis() - at < if (ok) okTtlMs else FAILURE_TTL_MS
    }

    // Stops LRU keyed on the rounded viewport box (stops are static-ish).
    private data class BboxKey(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)
    private val stopsCache = object : LinkedHashMap<BboxKey, Cached<List<TransitStop>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BboxKey, Cached<List<TransitStop>>>) = size > 32
    }

    // Departures cache keyed on stop id, with a short TTL (live data).
    private val departuresCache = object : LinkedHashMap<String, Cached<List<Departure>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached<List<Departure>>>) = size > 32
    }

    /**
     * Stops within the viewport box. Never throws — returns an empty list on any
     * failure. LRU-cached on the box rounded to ~100 m so small pans reuse the
     * previous fetch; a failed fetch is only remembered for [FAILURE_TTL_MS].
     */
    suspend fun stopsInBbox(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): List<TransitStop> {
        val key = BboxKey(round3(minLat), round3(minLon), round3(maxLat), round3(maxLon))
        synchronized(stopsCache) {
            stopsCache[key]?.let { if (it.isFresh(Long.MAX_VALUE)) return it.value }
        }
        val fetched = runCatching { fetchStops(minLat, minLon, maxLat, maxLon) }
        val stops = fetched.getOrDefault(emptyList())
        synchronized(stopsCache) { stopsCache[key] = Cached(stops, fetched.isSuccess) }
        return stops
    }

    /**
     * Live departures for [stopId]. Never throws — empty on failure. Cached for
     * [DEPARTURES_TTL_MS] on success and only [FAILURE_TTL_MS] on failure; pass
     * [force] (a manual refresh) to bypass the cache.
     */
    suspend fun departures(stopId: String, force: Boolean = false): List<Departure> {
        if (!force) {
            synchronized(departuresCache) {
                departuresCache[stopId]?.let { if (it.isFresh(DEPARTURES_TTL_MS)) return it.value }
            }
        }
        val fetched = runCatching { fetchDepartures(stopId) }
        val deps = fetched.getOrDefault(emptyList())
        synchronized(departuresCache) { departuresCache[stopId] = Cached(deps, fetched.isSuccess) }
        return deps
    }

    /**
     * Live board for the MOTIS stop nearest `(lat, lon)`. MOTIS stop ids share no
     * namespace with the baked `.transit` pack, so the offline planner locates a
     * stop's realtime data by proximity — the same trick
     * [com.vayunmathur.maps.util.TransitStopsViewModel.openNearestStop] uses.
     * Empty when nothing is near or the fetch fails.
     */
    suspend fun departuresNear(lat: Double, lon: Double): List<Departure> {
        // ~1.1 km search box, then the closest stop within it.
        val d = 0.01
        val nearest = stopsInBbox(lat - d, lon - d, lat + d, lon + d).minByOrNull { s ->
            val dLat = s.lat - lat
            val dLon = s.lon - lon
            dLat * dLat + dLon * dLon
        } ?: return emptyList()
        return departures(nearest.id)
    }

    private suspend fun fetchStops(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): List<TransitStop> = withContext(Dispatchers.IO) {
        // MOTIS map/stops takes the box corners as "lat,lon" min/max pairs.
        val url = "$BASE_URL/api/v1/map/stops" +
            "?min=${enc("$minLat,$minLon")}&max=${enc("$maxLat,$maxLon")}"
        val stops: List<MotisMapStop> = NetworkClient.getJson(url, REQUEST_HEADERS, useSystemTrust = true)
        stops.asSequence()
            .map { TransitStop(id = it.id, name = it.name.ifBlank { it.id }, lat = it.lat, lon = it.lon) }
            .distinctBy { it.id }
            .take(MAX_STOPS)
            .toList()
    }

    private suspend fun fetchDepartures(stopId: String): List<Departure> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/api/v1/stoptimes" +
            "?stopId=${enc(stopId)}&n=$DEPARTURE_COUNT&arriveBy=false"
        val resp: MotisStoptimes = NetworkClient.getJson(url, REQUEST_HEADERS, useSystemTrust = true)
        resp.stopTimes.mapNotNull { it.toDeparture() }
    }

    /**
     * Online transit journey planning (P11d fallback) via MOTIS
     * `GET /api/v1/plan`. Used when no offline `*.transit` index covers the
     * route. Never throws - returns null on any failure so the UI simply shows
     * no transit route. On-device only (needs the network); verified by shape.
     *
     * Per-leg geometry comes from MOTIS's encoded `legGeometry`, so an online
     * journey follows the same real roads and rails the offline planner draws. A
     * leg that carries no geometry falls back to a straight line between its
     * endpoints.
     */
    suspend fun planRoute(
        from: Position,
        to: Position,
    ): RouteService.Route? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$BASE_URL/api/v1/plan" +
                "?fromPlace=${enc("${from.latitude},${from.longitude}")}" +
                "&toPlace=${enc("${to.latitude},${to.longitude}")}&arriveBy=false"
            val resp: MotisPlanResponse =
                NetworkClient.getJson(url, REQUEST_HEADERS, useSystemTrust = true)
            val itinerary = resp.itineraries.firstOrNull() ?: return@runCatching null

            val polyline = mutableListOf<Position>()
            val steps = mutableListOf<RouteService.Step>()
            var totalDistance = 0.0
            var totalSeconds = 0L

            for (leg in itinerary.legs) {
                val f = leg.from ?: continue
                val t = leg.to ?: continue
                val fp = Position(f.lon, f.lat)
                val tp = Position(t.lon, t.lat)
                val geometry = leg.legGeometry
                    ?.let { decodePolyline(it.points, it.precision) }
                    ?.takeIf { it.size >= 2 }
                    ?: listOf(fp, tp)
                for (p in geometry) {
                    if (polyline.isEmpty() || polyline.last() != p) polyline.add(p)
                }

                val isTransit = !"WALK".equals(leg.mode, ignoreCase = true)
                totalDistance += leg.distance
                totalSeconds += leg.duration

                steps.add(
                    RouteService.Step(
                        distanceMeters = leg.distance,
                        staticDuration = leg.duration.seconds,
                        polyline = geometry,
                        navInstruction = RouteService.API.NavInstruction(
                            if (isTransit) RouteService.API.Maneuver.RIDE
                            else RouteService.API.Maneuver.MANEUVER_UNSPECIFIED,
                            ""
                        ),
                        travelMode = if (isTransit) RouteService.TravelMode.TRANSIT
                        else RouteService.TravelMode.WALK,
                        transitDetails = if (isTransit) RouteService.API.TransitDetails(
                            headsign = leg.headsign ?: "",
                            stopCount = 0,
                            transitLine = RouteService.API.TransitLine(
                                name = leg.routeShortName ?: leg.mode ?: "",
                                color = leg.routeColor?.ifBlank { null }
                                    ?.let { if (it.startsWith("#")) it else "#$it" }
                                    ?: "#FF0000"
                            ),
                            stopDetails = RouteService.API.StopDetails(
                                arrivalTime = formatClock(t.arrival ?: t.scheduledArrival),
                                departureTime = formatClock(f.departure ?: f.scheduledDeparture),
                                arrivalStop = RouteService.API.Stop(t.name ?: ""),
                                departureStop = RouteService.API.Stop(f.name ?: "")
                            ),
                            feedName = null
                        ) else null
                    )
                )
            }
            if (steps.isEmpty()) return@runCatching null
            RouteService.Route(
                duration = totalSeconds.seconds,
                distanceMeters = totalDistance,
                polyline = polyline,
                step = steps,
            )
        }.getOrNull()
    }

    /**
     * Decode an OTP/Google encoded polyline into positions.
     *
     * `precision` must come from the response: MOTIS uses 7 where Google's
     * original format used 5, and decoding one as the other misplaces every point
     * by a factor of 100. Values are zigzag-encoded deltas in chunks of five bits,
     * offset by 63 into printable ASCII. A truncated tail is dropped rather than
     * throwing — a half-decoded line still draws.
     *
     * The accumulator is a [Long] because at precision 7 a zigzagged longitude
     * delta reaches ~2.4e9, which overflows a signed 32-bit int: a whole-degree
     * first delta is routine, and wrapping it silently yields a plausible-looking
     * coordinate on the wrong side of the planet.
     */
    internal fun decodePolyline(encoded: String, precision: Int): List<Position> {
        if (encoded.isEmpty()) return emptyList()
        val scale = 10.0.pow(precision)
        val out = ArrayList<Position>()
        var i = 0
        var lat = 0L
        var lon = 0L
        fun nextDelta(): Long? {
            var shift = 0
            var result = 0L
            while (true) {
                if (i >= encoded.length) return null
                val b = (encoded[i++].code - 63).toLong()
                result = result or ((b and 0x1f) shl shift)
                if (b < 0x20) break
                shift += 5
            }
            // Zigzag: the low bit carries the sign.
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (i < encoded.length) {
            lat += nextDelta() ?: break
            lon += nextDelta() ?: break
            out.add(Position(lon / scale, lat / scale))
        }
        return out
    }

    /** Map a MOTIS stoptime → app [Departure]; drop entries with no usable
     *  departure time. Delay is realtime − scheduled in whole minutes. */
    private fun MotisStopTime.toDeparture(): Departure? {
        val place = place ?: return null
        val scheduled = parseEpoch(place.scheduledDeparture ?: place.departure) ?: return null
        val realtime = parseEpoch(place.departure) ?: scheduled
        val delayMin = ((realtime - scheduled) / 60_000L).toInt()
        return Departure(
            line = routeShortName?.ifBlank { null } ?: agencyName?.ifBlank { null } ?: "",
            headsign = headsign?.ifBlank { null } ?: place.name ?: "",
            scheduledMillis = scheduled,
            realtimeMillis = realtime,
            delayMinutes = delayMin,
            realTime = realTime,
            platform = (place.track ?: place.scheduledTrack)?.ifBlank { null },
            mode = mode,
            routeColor = routeColor?.removePrefix("#")?.ifBlank { null },
            cancelled = cancelled,
            tripId = tripId?.ifBlank { null },
        )
    }

    /** Parse a MOTIS ISO-8601 timestamp (with zone offset) → epoch millis, or
     *  null. Tolerates a plain `...Z` instant too. */
    private fun parseEpoch(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(iso).toEpochMilli() }
            .getOrNull()
    }

    /**
     * A MOTIS ISO-8601 timestamp as a wall clock `HH:mm` in its own zone offset,
     * matching what the offline planner puts in `StopDetails` so the directions
     * UI can render either source without knowing which produced it.
     */
    private fun formatClock(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return runCatching {
            OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
        }.getOrDefault("")
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
