package com.vayunmathur.maps.util

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.SpecificFeature
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private external fun init(basePath: String, presentFeeds: Array<String>): Boolean
    private external fun findRouteNative(
            sLat: Double,
            sLon: Double,
            eLat: Double,
            eLon: Double,
            mode: Int,
            startTime: Long
    ): Array<RawStep>?
    /**
     * Offline transit journey planning (P11b): RAPTOR over the per-region
     * `<feed>.transit` index at `<basePath>/<feed>.transit`. `depSecs` is seconds
     * since local midnight, `weekday` is 0=Mon..6=Sun, `date` is yyyymmdd.
     * Returns walk + ride legs as [RawStep]s, or null when the feed is missing,
     * doesn't cover the endpoints, or no journey exists.
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
            date: Int
    ): Array<RawStep>?
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
            val lanePacked: IntArray
    )

    private var isInitialized = false
    /** Base dir (external files) holding downloaded packs incl. `*.transit`. */
    private var basePath: String? = null

    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return
        val path = context.getExternalFilesDir(null)?.absolutePath ?: return
        basePath = path
        Log.d("OfflineRouter", "Initializing with path: $path")

        val presentFeeds = context.assets.list("")?.filter {
            try { context.assets.list(it)?.contains("routes.txt") == true } catch(_: Exception) { false }
        }?.toTypedArray() ?: emptyArray()

        isInitialized = init(path, presentFeeds)
        Log.d("OfflineRouter", "Initialization result: $isInitialized")
        cacheDirPath = context.cacheDir.absolutePath
    }

    suspend fun getRoute(
            context: Context,
            route: SpecificFeature.Route,
            userPosition: Position,
            type: RouteService.TravelMode
    ): RouteService.Route =
            withContext(Dispatchers.Default) {
                val start = route.waypoints.first()?.position ?: userPosition
                val end = route.waypoints.last()?.position ?: userPosition
                return@withContext getRoute(context, start, end, type)
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

        val now = java.util.Calendar.getInstance()
        val depSecs = now.get(java.util.Calendar.HOUR_OF_DAY) * 3600 +
                now.get(java.util.Calendar.MINUTE) * 60 +
                now.get(java.util.Calendar.SECOND)
        // Calendar: SUNDAY=1..SATURDAY=7 -> Mon=0..Sun=6 for the index masks.
        val weekday = (now.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        val date = now.get(java.util.Calendar.YEAR) * 10000 +
                (now.get(java.util.Calendar.MONTH) + 1) * 100 +
                now.get(java.util.Calendar.DAY_OF_MONTH)

        for (feed in feeds) {
            val raw = try {
                findTransitRouteNative(
                        base, feed,
                        start.latitude, start.longitude,
                        end.latitude, end.longitude,
                        depSecs, weekday, date
                )
            } catch (_: Exception) {
                null
            }
            if (raw != null && raw.isNotEmpty()) {
                return@withContext buildRoute(context, raw, RouteService.TravelMode.TRANSIT)
            }
        }
        null
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

                val now = java.util.Calendar.getInstance()
                val secondsSinceMidnight = now.get(java.util.Calendar.HOUR_OF_DAY) * 3600 +
                        now.get(java.util.Calendar.MINUTE) * 60 +
                        now.get(java.util.Calendar.SECOND)
                val startTime10ms = secondsSinceMidnight * 100L

                val rawSteps =
                        findRouteNative(
                                start.latitude,
                                start.longitude,
                                end.latitude,
                                end.longitude,
                                mode.ordinal,
                                startTime10ms
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
                                            headsign = "", // Not stored yet
                                            stopCount = raw.stopCount,
                                            transitLine = RouteService.API.TransitLine(
                                                name = raw.roadName,
                                                color = raw.gtfsFeed.let { GTFSProvider.getRouteColor(context, it, raw.roadName) } ?: "#FF0000"
                                            ),
                                            stopDetails = RouteService.API.StopDetails(
                                                arrivalTime = "",
                                                departureTime = "",
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
