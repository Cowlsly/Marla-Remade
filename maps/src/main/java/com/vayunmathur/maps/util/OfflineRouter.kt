package com.vayunmathur.maps.util

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.util.ConnectivityMonitor
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.data.transit.TransitousDataSource
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.geojson.Position

object OfflineRouter {
    private var serverPort = 0
    val trafficTileUrl: String get() = if (serverPort > 0) "http://localhost:$serverPort/traffic/{z}/{x}/{y}" else ""

    init {
        System.loadLibrary("offlinerouter")
        startLocalTileServer()
    }

    private fun startLocalTileServer() {
        Thread {
            try {
                // Bind to loopback ONLY. The previous `ServerSocket(0)` defaulted
                // to 0.0.0.0 which let any app on the device (or anything on the
                // local network) hit /traffic/{z}/{x}/{y}.
                val serverSocket = java.net.ServerSocket(0, 50, InetAddress.getLoopbackAddress())
                serverPort = serverSocket.localPort
                Log.d("OFFLINE_ROUTER", "Tile server started on port $serverPort (loopback only)")
                // Hand each client to a small pool so a slow tile doesn't block
                // MapLibre's concurrent tile requests behind the global mutex.
                val pool = Executors.newFixedThreadPool(4)
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept()
                    // Prevent a half-open / hung client from holding a worker forever.
                    runCatching { client.soTimeout = 5_000 }
                    pool.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e("OFFLINE_ROUTER", "Tile server error", e)
            }
        }.start()
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            val reader = client.getInputStream().bufferedReader()
            val firstLine = reader.readLine() ?: return
            
            // Expected: GET /traffic/{z}/{x}/{y} HTTP/1.1
            val parts = firstLine.split(" ")
            if (parts.size >= 2 && parts[0] == "GET") {
                val pathParts = parts[1].removePrefix("/traffic/").split("/")
                if (pathParts.size == 3) {
                    val z = pathParts[0].toIntOrNull() ?: 0
                    val x = pathParts[1].toIntOrNull() ?: 0
                    val y = pathParts[2].substringBefore("?").toIntOrNull() ?: 0
                    
                    val bytes = getTrafficTileNative(z, x, y)
                    val output = client.getOutputStream()
                    if (bytes != null) {
                        output.write(("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/vnd.mapbox-vector-tile\r\n" +
                                "Content-Encoding: gzip\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Access-Control-Allow-Origin: *\r\n\r\n").toByteArray())
                        output.write(bytes)
                    } else {
                        output.write("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            Log.e("OFFLINE_ROUTER", "Error handling client", e)
        } finally {
            client.close()
        }
    }

    private external fun init(basePath: String): Boolean
    private external fun findRouteNative(
            sLat: Double,
            sLon: Double,
            eLat: Double,
            eLon: Double,
            mode: Int
    ): Array<RawStep>?
    /**
     * Offline transit journey planning (P11b): RAPTOR over the per-region
     * `<feed>.transit` index at `<basePath>/<feed>.transit`. `depSecs` is seconds
     * since midnight, `weekday` is 0=Mon..6=Sun, `date` is yyyymmdd — all in the
     * **feed's** timezone (see [getFeedTimezoneNative]), since the index is
     * world-merged. `prevWeekday`/`prevDate` describe the preceding service day,
     * whose GTFS `>24:00:00` trips run into the query day.
     *
     * `overlay*` carry MOTIS realtime so the planner skips cancelled trips and
     * uses live times; pass empty arrays for a schedule-only plan.
     * `overlayCoords` is interleaved `[lat, lon, ...]` and `overlayTimes` is
     * interleaved `[schedSecs, delaySecs, cancelled, ...]`, both parallel to
     * `overlayRoutes`.
     *
     * Returns walk + wait + ride legs as [RawStep]s, or null when the feed is
     * missing, doesn't cover the endpoints, or no journey exists.
     */
    private external fun findTransitRouteNative(
            basePath: String,
            feed: String,
            sLat: Double,
            sLon: Double,
            eLat: Double,
            eLon: Double,
            depSecs: Int,
            weekday: Int,
            date: Int,
            prevWeekday: Int,
            prevDate: Int,
            overlayCoords: DoubleArray,
            overlayRoutes: Array<String>,
            overlayTimes: IntArray
    ): Array<RawStep>?
    /**
     * Offline scheduled departure board: upcoming departures from the stop
     * nearest `(lat,lon)` in `<basePath>/<feed>.transit`. Time and overlay
     * arguments are as in [findTransitRouteNative].
     * Returns null when the feed is missing or doesn't cover the point.
     */
    private external fun getStopDeparturesNative(
            basePath: String,
            feed: String,
            lat: Double,
            lon: Double,
            depSecs: Int,
            weekday: Int,
            date: Int,
            prevWeekday: Int,
            prevDate: Int,
            overlayCoords: DoubleArray,
            overlayRoutes: Array<String>,
            overlayTimes: IntArray,
            max: Int
    ): Array<RawDeparture>?
    /**
     * IANA timezone of the feed covering `(lat,lon)` in the given pack, or null
     * when the pack is stale/absent, doesn't cover the point, or its GTFS had no
     * `agency.txt`. Callers resolve this before deriving any query times.
     */
    private external fun getFeedTimezoneNative(
            basePath: String,
            feed: String,
            lat: Double,
            lon: Double
    ): String?

    /**
     * MOTIS/Transitous id of the stop nearest `(lat, lon)` in the baked v5 pack,
     * or null when the pack is absent, predates v5, doesn't cover the point, or
     * its feed's Transitous source name was unknown at build time.
     *
     * A purely local lookup. It exists because the departure board fetches its
     * realtime overlay before it knows which stop the board is for, so it has to
     * name the stop up front — and since `/api/v1/map/stops` is gone, there is no
     * longer any network way to turn a coordinate into a MOTIS id.
     */
    private external fun nearestStopMotisIdNative(
            basePath: String,
            feed: String,
            lat: Double,
            lon: Double
    ): String?
    private external fun updateTrafficNative(
            edgeIds: LongArray,
            speeds: ByteArray,
            packedSquare: Int
    )
    external fun getTrafficSegmentsNative(): DoubleArray
    private external fun notifyTrafficFetchFinishedNative(packedSquare: Int)
    external fun getTrafficTileNative(z: Int, x: Int, y: Int): ByteArray?

    private val _trafficVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val trafficVersion = _trafficVersion.asStateFlow()

    private var cacheDirPath: String? = null
    private var trafficUpdateJob: kotlinx.coroutines.Job? = null

    fun notifyTrafficUpdated() {
        trafficUpdateJob?.cancel()
        trafficUpdateJob = trafficScope.launch {
            _trafficVersion.value++
        }
    }

    external fun ensureTrafficLoadedNative(lat: Double, lon: Double, forceAsync: Boolean)

    private val trafficScope = CoroutineScope(Dispatchers.IO)

    @Keep
    private fun fetchTrafficData(
            minLat: Double,
            minLon: Double,
            maxLat: Double,
            maxLon: Double,
            packedSquare: Int,
            forceAsync: Boolean
    ) {
        Log.d(
                "TRAFFIC_DATA",
                "fetchTrafficData START: bbox ($minLat,$minLon)-($maxLat,$maxLon) packed=$packedSquare forceAsync=$forceAsync"
        )
        
        val block: suspend () -> Unit = {
            try {
                val (status, bytes) =
                        NetworkClient.performRequestBytes(
                                url =
                                        "https://api.vayunmathur.com/maps/traffic?min_lat=$minLat&min_lon=$minLon&max_lat=$maxLat&max_lon=$maxLon"
                        )
                Log.d(
                        "TRAFFIC_DATA",
                        "fetchTrafficData NETWORK DONE: status=$status, size=${bytes.size}"
                )
                // Response layout: n * 8-byte LE u64 edge IDs, then n * 1-byte speeds.
                if (status == 200 && bytes.size >= 9) {
                    val n = bytes.size / 9
                    val edgeIds = LongArray(n)
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) edgeIds[i] = buffer.long
                    val speeds = ByteArray(n)
                    buffer.get(speeds)
                    Log.d("TRAFFIC_DATA", "fetchTrafficData PROCESSING: $n edges")
                    updateTrafficNative(edgeIds, speeds, packedSquare)
                    notifyTrafficUpdated()
                } else {
                    Log.w("TRAFFIC_DATA", "fetchTrafficData NO DATA: status=$status")
                    notifyTrafficFetchFinishedNative(packedSquare)
                }
            } catch (e: Exception) {
                Log.e("TRAFFIC_DATA", "fetchTrafficData ERROR", e)
                notifyTrafficFetchFinishedNative(packedSquare)
            }
            Log.d("TRAFFIC_DATA", "fetchTrafficData END: packed=$packedSquare")
        }

        if (forceAsync) {
            trafficScope.launch { block() }
        } else {
            // Previously called runBlocking(Dispatchers.IO) which blocked the
            // native caller's thread (often a Dispatchers.Default worker via
            // getRoute) for an entire 60s HTTP round-trip. That starved the
            // Default pool. Always async; the native side reacts to
            // notifyTrafficUpdated / notifyTrafficFetchFinishedNative when the
            // HTTP response is processed.
            trafficScope.launch { block() }
        }
    }

    class RawStep
    @Keep
    constructor(
            val maneuverId: Int,
            val roadName: String,
            val distanceMm: Long,
            val duration10ms: Long,
            val geometry: DoubleArray,
            val speedRatio: Double,
            val isTransit: Boolean,
            val gtfsFeed: String?,
            val stopCode: String?,
            val endStopCode: String?,
            val stopCount: Int,
            /** Packed turn lanes: one int per lane, `dirMask * 2 + valid`, where
             * `dirMask` is a bitmask of Maneuver ordinals the lane offers. */
            val lanePacked: IntArray,
            // Transit-only tail. The JNI ctor descriptor is shared with the
            // driving path, which passes null/0 — keep these LAST so adding to
            // them never renumbers the arguments above.
            /** GTFS `trip_headsign` of the ridden trip. */
            val headsign: String?,
            /** GTFS `route_color` packed as 0xRRGGBB, or 0 when absent. */
            val routeColor: Int,
            /** Departure, seconds since feed-local midnight (0 when unknown). */
            val depSecs: Int,
            /** Arrival, seconds since feed-local midnight (0 when unknown). */
            val arrSecs: Int,
            /**
             * MOTIS/Transitous id of the ride's board stop, baked into the v5
             * pack. Null on a walk/wait leg, on a pre-v5 pack, or when the feed's
             * Transitous source name was unknown at build time — in which case the
             * realtime overlay simply has nothing to ask about for this leg.
             */
            val boardStopId: String?,
            /** MOTIS/Transitous id of the ride's alight stop. See [boardStopId]. */
            val alightStopId: String?,
    )

    /** One offline scheduled departure from the baked `.transit` index. */
    class RawDeparture
    @Keep
    constructor(
            val routeName: String,
            val headsign: String,
            val feed: String,
            val stopCode: String,
            /** GTFS route colour as packed 0xRRGGBB, or 0 when absent. */
            val routeColor: Int,
            /** GTFS route_type. */
            val routeType: Int,
            /** Scheduled departure, seconds since feed-local midnight. */
            val depSecs: Int,
            /** Realtime shift in seconds; 0 without live data. */
            val delaySecs: Int,
            val cancelled: Boolean,
            /** Whether the realtime overlay covered this departure. */
            val realTime: Boolean
    )

    /**
     * MOTIS realtime, flattened for the JNI overlay arguments. [coords] is
     * interleaved `[lat, lon, ...]`, [times] is interleaved
     * `[schedSecs, delaySecs, cancelled, ...]`, and both are parallel to
     * [routes]. Empty means "plan against the schedule only".
     */
    private class Overlay(
            val coords: DoubleArray,
            val routes: Array<String>,
            val times: IntArray,
    ) {
        val isEmpty: Boolean get() = routes.isEmpty()

        companion object {
            val EMPTY = Overlay(DoubleArray(0), emptyArray(), IntArray(0))
        }
    }

    private var isInitialized = false
    /** Base dir (external files) holding downloaded packs incl. `*.transit`. */
    private var basePath: String? = null

    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return
        val path = context.getExternalFilesDir(null)?.absolutePath ?: return
        basePath = path
        Log.d("OfflineRouter", "Initializing with path: $path")

        isInitialized = init(path)
        Log.d("OfflineRouter", "Initialization result: $isInitialized")
        cacheDirPath = context.cacheDir.absolutePath
    }

    /**
     * Force a re-load of the routing graph from disk. Call after the single
     * global routing graph (P16) finishes downloading so the freshly downloaded
     * nodes.bin/edges.bin/… replace whatever was (or wasn't) loaded at startup.
     * Re-init is safe: the Rust side atomically swaps the graph behind its lock.
     */
    @Synchronized
    fun reload(context: Context) {
        isInitialized = false
        initialize(context)
    }

    /**
     * Plan a route for any [mode]. TRANSIT goes to the on-device RAPTOR planner
     * and nowhere else — there is no online routing fallback, so a journey the
     * pack cannot plan yields no transit route. Every other mode goes to the
     * road graph via [getRouteMulti].
     *
     * This is the **only** correct entry point for a caller whose mode is not a
     * literal: the road graph carries no timetable, so TRANSIT must never reach
     * [getRouteMulti]. Routing every mode-agnostic caller through here is what
     * guarantees that.
     */
    suspend fun getRouteForMode(
            context: Context,
            route: SpecificFeature.Route,
            userPosition: Position,
            mode: RouteService.TravelMode,
    ): RouteService.Route? = withContext(Dispatchers.Default) {
        if (mode != RouteService.TravelMode.TRANSIT) {
            return@withContext getRouteMulti(context, route, userPosition, mode)
        }
        val positions = route.waypoints.map { it?.position ?: userPosition }
        if (positions.size < 2) return@withContext null
        val start = positions.first()
        val end = positions.last()
        getTransitRouteOffline(context, start, end)
    }

    /**
     * Offline-only multi-waypoint chaining. Replaces the old server-side
     * routing that handled intermediates remotely.
     * Positions = route.waypoints.map { it?.position ?: userPosition }.
     * Chains A->B, B->C ... using [getRoute] and concatenates polylines
     * (dedup join), steps, and sums distance/duration. Returns null if
     * any leg fails or if <2 positions.
     */
    suspend fun getRouteMulti(context: Context, route: SpecificFeature.Route, userPosition: Position, type: RouteService.TravelMode): RouteService.Route? = withContext(Dispatchers.Default) {
        val positions = route.waypoints.map { it?.position ?: userPosition }
        if (positions.size < 2) return@withContext null
        val legs = mutableListOf<RouteService.Route>()
        for (i in 0 until positions.size - 1) {
            val leg = try { getRoute(context, positions[i], positions[i+1], type) } catch (_: Exception) { return@withContext null }
            legs.add(leg)
        }
        if (legs.isEmpty()) return@withContext null
        if (legs.size == 1) return@withContext legs.first()
        val combinedPolyline = mutableListOf<Position>()
        val combinedSteps = mutableListOf<RouteService.Step>()
        var totalDist = 0.0
        var totalSec = 0L
        for (leg in legs) {
            if (combinedPolyline.isEmpty()) combinedPolyline.addAll(leg.polyline)
            else {
                val first = leg.polyline.firstOrNull()
                if (first != null && combinedPolyline.lastOrNull() == first) combinedPolyline.addAll(leg.polyline.drop(1))
                else combinedPolyline.addAll(leg.polyline)
            }
            combinedSteps.addAll(leg.step)
            totalDist += leg.distanceMeters
            totalSec += leg.duration.inWholeSeconds
        }
        RouteService.Route(duration = totalSec.seconds, distanceMeters = totalDist, polyline = combinedPolyline, step = combinedSteps)
    }

    /**
     * Offline transit routing (P11d): plan a journey with the on-device RAPTOR
     * planner over any downloaded per-region `*.transit` index that covers the
     * endpoints. Returns null when no index is present/covering or no journey is
     * found — the caller then falls back to the P10 online Transitous planner.
     *
     * Runs at most **two** RAPTOR passes: a schedule-only plan, then, when the
     * device is online, a replan against MOTIS realtime for the stops that plan
     * actually touches, so a cancelled or badly delayed trip is avoided. If the
     * replan finds nothing we keep the schedule-only journey rather than
     * iterating.
     */
    suspend fun getTransitRouteOffline(
            context: Context,
            start: Position,
            end: Position
    ): RouteService.Route? = withContext(Dispatchers.Default) {
        if (!isInitialized) initialize(context)
        val base = basePath ?: return@withContext null
        val feeds = File(base)
                .listFiles { f -> f.isFile && f.name.endsWith(".transit") }
                ?.map { it.name.removeSuffix(".transit") }
                ?: emptyList()
        if (feeds.isEmpty()) return@withContext null

        for (feed in feeds) {
            // The index is world-merged, so query times must be in the feed's
            // timezone. Journeys spanning two zones use the origin's — a known
            // limitation, but far better than always using the device's.
            val clock = transitClock(
                    runCatching {
                        getFeedTimezoneNative(base, feed, start.latitude, start.longitude)
                    }.getOrNull()
            )
            val plan = { overlay: Overlay ->
                try {
                    findTransitRouteNative(
                            base, feed,
                            start.latitude, start.longitude,
                            end.latitude, end.longitude,
                            clock.depSecs, clock.weekday, clock.date,
                            clock.prevWeekday, clock.prevDate,
                            overlay.coords, overlay.routes, overlay.times
                    )
                } catch (_: Exception) {
                    null
                }
            }

            val scheduled = plan(Overlay.EMPTY)
            if (scheduled == null || scheduled.isEmpty()) continue

            val overlay = realtimeOverlay(context, journeyStops(scheduled), clock)
            val raw = if (overlay.isEmpty) scheduled else plan(overlay) ?: scheduled
            return@withContext buildRoute(context, raw, RouteService.TravelMode.TRANSIT)
        }
        null
    }

    /**
     * Board and alight stops of every ride in a planned journey, as
     * `(position, MOTIS id)`.
     *
     * The id is baked into the v5 pack, which is what lets the overlay name a stop
     * to `/stoptimes` directly. A leg whose pack predates v5, or whose feed's
     * Transitous source name the build did not know, carries no id and is dropped:
     * without one there is no way to ask about it now that the `/map/stops`
     * proximity lookup is gone, and it simply stays schedule-only.
     */
    private fun journeyStops(steps: Array<RawStep>): List<Pair<Position, String>> =
            steps.filter { it.isTransit && it.geometry.size >= 4 }
                    .flatMap { s ->
                        val g = s.geometry
                        listOfNotNull(
                                s.boardStopId?.ifBlank { null }
                                        ?.let { Position(g[0], g[1]) to it },
                                s.alightStopId?.ifBlank { null }
                                        ?.let { Position(g[g.size - 2], g[g.size - 1]) to it },
                        )
                    }
                    .distinctBy { it.second }

    /**
     * Fetch MOTIS boards for [stops] concurrently and flatten them into an
     * [Overlay]. Each stop is named by its baked MOTIS id, so this is one
     * `/stoptimes` call per stop with no proximity round-trip.
     *
     * Returns [Overlay.EMPTY] when the device is offline — without that gate every
     * offline plan would pay a full HTTP timeout per stop before `runCatching`
     * swallowed it.
     */
    private suspend fun realtimeOverlay(
            context: Context,
            stops: List<Pair<Position, String>>,
            clock: TransitClock,
    ): Overlay {
        if (stops.isEmpty() || !ConnectivityMonitor.isOnline(context)) return Overlay.EMPTY
        val boards = coroutineScope {
            stops.map { (p, motisId) ->
                async(Dispatchers.IO) {
                    p to runCatching {
                        TransitousDataSource.departures(motisId)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }

        val coords = mutableListOf<Double>()
        val routes = mutableListOf<String>()
        val times = mutableListOf<Int>()
        for ((pos, deps) in boards) {
            for (d in deps) {
                if (d.line.isBlank()) continue
                val delaySecs = ((d.realtimeMillis - d.scheduledMillis) / 1000L).toInt()
                if (delaySecs == 0 && !d.cancelled) continue
                coords.add(pos.latitude)
                coords.add(pos.longitude)
                routes.add(d.line)
                times.add(((d.scheduledMillis - clock.midnightMillis) / 1000L).toInt())
                times.add(delaySecs)
                times.add(if (d.cancelled) 1 else 0)
            }
        }
        if (routes.isEmpty()) return Overlay.EMPTY
        return Overlay(coords.toDoubleArray(), routes.toTypedArray(), times.toIntArray())
    }

    /**
     * The stop nearest `(lat, lon)` from the baked `*.transit` packs, as a
     * [TransitStop] whose id is the MOTIS/Transitous id so the realtime board can
     * query it. Null when no pack covers the point.
     *
     * Reached from a tapped station POI, which carries no stop id of its own. This
     * is a purely local lookup — `/api/v1/map/stops`, which used to answer
     * "what stop is here", is gone.
     */
    suspend fun nearestStop(
            context: Context,
            lat: Double,
            lon: Double,
    ): TransitStop? = withContext(Dispatchers.Default) {
        if (!isInitialized) initialize(context)
        val base = basePath ?: return@withContext null
        val feeds = File(base)
                .listFiles { f -> f.isFile && f.name.endsWith(".transit") }
                ?.map { it.name.removeSuffix(".transit") }
                ?: emptyList()
        for (feed in feeds) {
            val id = runCatching {
                nearestStopMotisIdNative(base, feed, lat, lon)
            }.getOrNull()?.ifBlank { null } ?: continue
            // The board resolves its own stop from the coordinate, so the name is
            // only a label until it loads; the MOTIS id is the part that matters.
            return@withContext TransitStop(id = id, name = id, lat = lat, lon = lon)
        }
        null
    }

    /**
     * Departure board from the baked `*.transit` index for the stop nearest
     * `(lat,lon)`. Scheduled times come from the pack; when the device is online
     * the MOTIS board for that stop is folded in as a realtime overlay, so
     * `delayMinutes`/`realTime`/`cancelled` are live. Returns an empty list when
     * no pack covers the point.
     */
    suspend fun getStopDeparturesOffline(
            context: Context,
            lat: Double,
            lon: Double,
            max: Int = 30
    ): List<Departure> = withContext(Dispatchers.Default) {
        if (!isInitialized) initialize(context)
        val base = basePath ?: return@withContext emptyList()
        val feeds = File(base)
                .listFiles { f -> f.isFile && f.name.endsWith(".transit") }
                ?.map { it.name.removeSuffix(".transit") }
                ?: emptyList()
        if (feeds.isEmpty()) return@withContext emptyList()

        val all = mutableListOf<Departure>()
        for (feed in feeds) {
            val clock = transitClock(
                    runCatching { getFeedTimezoneNative(base, feed, lat, lon) }.getOrNull()
            )
            // The board is fetched before we know which stop it is for, so name the
            // stop up front from the pack. No pack id (pre-v5, or a feed whose
            // Transitous source name the build did not know) means no realtime, and
            // the board stays schedule-only.
            //
            // Only the nearest stop's board is fetched, even though the offline
            // board aggregates co-located platforms within 150 m. That is
            // deliberate: the Rust overlay matches a delay to a stop within 60 m,
            // tight enough that adjacent platforms don't collide, so a neighbouring
            // platform's realtime could not be attributed anyway without carrying
            // per-stop coordinates back out of the board.
            val motisId = runCatching {
                nearestStopMotisIdNative(base, feed, lat, lon)
            }.getOrNull()?.ifBlank { null }
            val overlay = if (motisId == null) {
                Overlay.EMPTY
            } else {
                realtimeOverlay(context, listOf(Position(lon, lat) to motisId), clock)
            }
            val raw = try {
                getStopDeparturesNative(
                        base, feed, lat, lon,
                        clock.depSecs, clock.weekday, clock.date,
                        clock.prevWeekday, clock.prevDate,
                        overlay.coords, overlay.routes, overlay.times, max
                )
            } catch (_: Exception) {
                null
            } ?: continue
            for (d in raw) {
                val scheduled = clock.midnightMillis + d.depSecs.toLong() * 1000L
                all.add(
                        Departure(
                                line = d.routeName,
                                headsign = d.headsign,
                                scheduledMillis = scheduled,
                                realtimeMillis = scheduled + d.delaySecs * 1000L,
                                delayMinutes = d.delaySecs / 60,
                                realTime = d.realTime,
                                platform = null,
                                mode = gtfsRouteTypeToMode(d.routeType),
                                routeColor = if (d.routeColor == 0) null
                                             else String.format("%06X", d.routeColor and 0xFFFFFF),
                                cancelled = d.cancelled,
                        )
                )
            }
        }
        all.sortBy { it.realtimeMillis }
        all.take(max)
    }

    /** Map a GTFS `route_type` (base + extended ranges) to a coarse mode label. */
    private fun gtfsRouteTypeToMode(t: Int): String = when (t) {
        0, 5, 900 -> "TRAM"
        1, in 400..499 -> "SUBWAY"
        2, in 100..199 -> "RAIL"
        3, in 200..299, in 700..799, 800 -> "BUS"
        4, 1000, 1200 -> "FERRY"
        6, 1300 -> "AERIAL"
        7, 1400 -> "FUNICULAR"
        11 -> "TROLLEYBUS"
        12 -> "MONORAIL"
        else -> "TRANSIT"
    }

    suspend fun getRoute(
            context: Context,
            start: Position,
            end: Position,
            mode: RouteService.TravelMode
    ): RouteService.Route =
            withContext(Dispatchers.Default) {
                Log.d("OfflineRouter", "getRoute: mode=$mode, start=$start, end=$end")
                if (!isInitialized) {
                    initialize(context)
                }
                Log.d("OfflineRouter", "isInitialized=$isInitialized")

                val rawSteps =
                        findRouteNative(
                                start.latitude,
                                start.longitude,
                                end.latitude,
                                end.longitude,
                                mode.ordinal
                        )
                                ?: throw IllegalStateException("No route found")

                buildRoute(context, rawSteps, mode)
            }

    /**
     * Convert native [RawStep]s into a [RouteService.Route]: decode geometry,
     * localize maneuver text, attach transit details, and coalesce consecutive
     * same-road maneuvers. Shared by the driving/walking [findRouteNative] path
     * and the offline transit [findTransitRouteNative] path.
     */
    private fun buildRoute(
            context: Context,
            rawSteps: Array<RawStep>,
            mode: RouteService.TravelMode
    ): RouteService.Route {
                val fullPolyline = mutableListOf<Position>()
                val processedSteps =
                        rawSteps.map { raw ->
                            val positions = mutableListOf<Position>()
                            for (i in raw.geometry.indices step 2) {
                                val pos = Position(raw.geometry[i], raw.geometry[i + 1])
                                positions.add(pos)
                                if (fullPolyline.isEmpty() || fullPolyline.last() != pos) {
                                    fullPolyline.add(pos)
                                }
                            }

                            val maneuver =
                                    RouteService.API.Maneuver.entries.getOrElse(raw.maneuverId) {
                                        RouteService.API.Maneuver.MANEUVER_UNSPECIFIED
                                    }
                            // Decode packed turn lanes into ordered left→right
                            // lane guidance. Each int is `dirMask * 2 + valid`,
                            // where dirMask is a bitmask of Maneuver ordinals the
                            // lane offers (real OSM turn:lanes can allow several
                            // turns, e.g. through+right) and bit0 is the active
                            // flag (lane leads onto the taken route).
                            val lanes = raw.lanePacked.map { code ->
                                val active = (code and 1) == 1
                                val mask = code ushr 1
                                val directions =
                                        RouteService.API.Maneuver.entries.filter { m ->
                                            (mask and (1 shl m.ordinal)) != 0
                                        }
                                RouteService.API.Lane(
                                        directions = directions.ifEmpty {
                                            listOf(RouteService.API.Maneuver.STRAIGHT)
                                        },
                                        active = active,
                                )
                            }
                            val hasName = raw.roadName.isNotBlank()
                            val instructionText =
                                    when (maneuver) {
                                        RouteService.API.Maneuver.DEPART ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_depart,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_depart_unnamed
                                                        )
                                        RouteService.API.Maneuver.STRAIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_straight,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_straight_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_turn_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_turn_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_SLIGHT_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_slight_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string
                                                                        .maneuver_turn_slight_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_SLIGHT_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_slight_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string
                                                                        .maneuver_turn_slight_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_SHARP_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_sharp_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string
                                                                        .maneuver_turn_sharp_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_SHARP_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_sharp_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string
                                                                        .maneuver_turn_sharp_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.UTURN_LEFT,
                                        RouteService.API.Maneuver.UTURN_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_uturn,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_uturn_unnamed
                                                        )
                                        RouteService.API.Maneuver.MERGE ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_merge,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_merge_unnamed
                                                        )
                                        RouteService.API.Maneuver.RAMP_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_ramp_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_ramp_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.RAMP_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_ramp_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_ramp_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.FORK_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_fork_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_fork_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.FORK_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_fork_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_fork_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.ROUNDABOUT_LEFT,
                                        RouteService.API.Maneuver.ROUNDABOUT_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_roundabout,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_roundabout_unnamed
                                                        )
                                        RouteService.API.Maneuver.WAIT -> {
                                            val waitSeconds = raw.duration10ms / 100
                                            val waitText = if (waitSeconds >= 60) "${waitSeconds / 60} min" else "$waitSeconds sec"
                                            if (raw.stopCode != null && raw.stopCode.isNotBlank())
                                                context.getString(R.string.maneuver_wait_at, waitText, raw.roadName, raw.stopCode)
                                            else
                                                context.getString(R.string.maneuver_wait, waitText, raw.roadName)
                                        }
                                        else ->
                                            if (raw.isTransit && raw.stopCode != null && raw.endStopCode != null)
                                                context.getString(R.string.maneuver_ride_transit, raw.roadName, raw.stopCode, raw.endStopCode, raw.stopCount)
                                            else if (hasName)
                                                context.getString(
                                                        R.string.maneuver_unspecified,
                                                        raw.roadName
                                                )
                                            else
                                                context.getString(
                                                        R.string
                                                                .maneuver_unspecified_unnamed
                                                )
                                    }

                            RouteService.Step(
                                    distanceMeters = raw.distanceMm / 1000.0,
                                    staticDuration = (raw.duration10ms / 100.0).seconds,
                                    polyline = positions,
                                    navInstruction =
                                            RouteService.API.NavInstruction(
                                                    maneuver,
                                                    instructionText
                                            ),
                                    travelMode = if (raw.isTransit) RouteService.TravelMode.TRANSIT
                                    else if (mode == RouteService.TravelMode.TRANSIT) RouteService.TravelMode.WALK
                                    else mode,
                                    speedRatio = raw.speedRatio,
                                    lanes = lanes,
                                    transitDetails = if (raw.isTransit && raw.gtfsFeed != null && raw.stopCode != null) {
                                        RouteService.API.TransitDetails(
                                            headsign = raw.headsign ?: "",
                                            stopCount = raw.stopCount,
                                            transitLine = RouteService.API.TransitLine(
                                                name = raw.roadName,
                                                // The index carries route_color for
                                                // every feed; GTFSProvider only sees
                                                // the bundled APK asset feed, so it is
                                                // just a fallback now.
                                                color = raw.routeColor
                                                    .takeIf { it != 0 }
                                                    ?.let { "#%06X".format(it and 0xFFFFFF) }
                                                    ?: GTFSProvider.getRouteColor(context, raw.gtfsFeed, raw.roadName)
                                                    ?: "#FF0000"
                                            ),
                                            stopDetails = RouteService.API.StopDetails(
                                                arrivalTime = formatServiceTime(raw.arrSecs),
                                                departureTime = formatServiceTime(raw.depSecs),
                                                arrivalStop = RouteService.API.Stop(raw.endStopCode ?: ""),
                                                departureStop = RouteService.API.Stop(raw.stopCode)
                                            ),
                                            feedName = raw.gtfsFeed
                                        )
                                    } else null
                            )
                        }

                // Coalesce consecutive maneuvers that stay on the same road.
                // The native router can emit a chain of small "slight left /
                // slight right" entries along a curving stretch of road
                // (e.g. El Camino Real bending through Palo Alto) where
                // the road name never changes — visually that's "stay on
                // the same road", not a series of turns. Merge those into
                // a single step whose polyline + distance + duration is the
                // sum of the merged steps.
                val coalescedSteps = mutableListOf<RouteService.Step>()
                for (step in processedSteps) {
                    val prev = coalescedSteps.lastOrNull()
                    // Smart-cast prev to non-null in the merge branch by
                    // gating on prev != null first.
                    if (prev != null &&
                        prev.travelMode == step.travelMode &&
                        step.travelMode != RouteService.TravelMode.TRANSIT &&
                        step.navInstruction.maneuver in NON_TURNING_MANEUVERS &&
                        // Road name unchanged (instruction text is
                        // road-name-templated, so equal strings ⇒ same road).
                        sameRoadName(prev.navInstruction.instructions, step.navInstruction.instructions)
                    ) {
                        coalescedSteps[coalescedSteps.lastIndex] = prev.copy(
                            distanceMeters = prev.distanceMeters + step.distanceMeters,
                            staticDuration = prev.staticDuration + step.staticDuration,
                            polyline = mergePolylines(prev.polyline, step.polyline),
                        )
                    } else {
                        coalescedSteps.add(step)
                    }
                }

                return RouteService.Route(
                        duration =
                                coalescedSteps.sumOf { it.staticDuration.inWholeSeconds }.seconds,
                        distanceMeters = coalescedSteps.sumOf { it.distanceMeters },
                        polyline = fullPolyline,
                        step = coalescedSteps
                )
    }

    /**
     * Maneuvers that we treat as "still on the same road" when their
     * instruction text doesn't change between steps. A SHARP turn or a
     * RAMP / FORK / MERGE / ROUNDABOUT is always a real maneuver even if
     * the road name happens to match.
     */
    private val NON_TURNING_MANEUVERS = setOf(
        RouteService.API.Maneuver.STRAIGHT,
        RouteService.API.Maneuver.TURN_SLIGHT_LEFT,
        RouteService.API.Maneuver.TURN_SLIGHT_RIGHT,
        RouteService.API.Maneuver.NAME_CHANGE,
        RouteService.API.Maneuver.MANEUVER_UNSPECIFIED,
    )

    /**
     * Two adjacent maneuvers are considered "on the same road" when the
     * instruction strings match. Instruction text is templated from the
     * road name (see the maneuver_* string templates), so identical
     * instruction strings ⇒ same road.
     */
    private fun sameRoadName(prev: String, curr: String): Boolean =
        prev.isNotBlank() && prev == curr

    /** Concatenate two step polylines, skipping the duplicate join point. */
    private fun mergePolylines(
        a: List<org.maplibre.spatialk.geojson.Position>,
        b: List<org.maplibre.spatialk.geojson.Position>,
    ): List<org.maplibre.spatialk.geojson.Position> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        return if (a.last() == b.first()) a + b.drop(1) else a + b
    }
}
