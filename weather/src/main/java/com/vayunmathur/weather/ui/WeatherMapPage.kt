package com.vayunmathur.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.FilledTonalIconButton
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.R
import com.vayunmathur.weather.Route
import com.vayunmathur.weather.network.RegionTimezone
import com.vayunmathur.weather.network.WeatherApi
import com.vayunmathur.weather.domain.WeatherMetric
import com.vayunmathur.weather.domain.colorRamp
import com.vayunmathur.weather.domain.formatInstantInZone
import com.vayunmathur.weather.domain.map.DwdIconGlobal
import com.vayunmathur.weather.domain.map.OmMapMetadata
import com.vayunmathur.weather.domain.map.OmTilesNative
import com.vayunmathur.weather.domain.map.colorizeToBitmap
import com.vayunmathur.weather.domain.map.fetchOmMapMetadata
import com.vayunmathur.weather.domain.map.omFileUrl
import com.vayunmathur.weather.domain.map.omVariable
import com.vayunmathur.weather.domain.mapMetrics
import com.vayunmathur.weather.domain.metricValueFormatter
import com.vayunmathur.weather.platform.rememberPressureUnit
import com.vayunmathur.weather.platform.rememberTempUnit
import com.vayunmathur.weather.platform.rememberUse24Hour
import com.vayunmathur.weather.platform.rememberWindUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.GestureOptions
import com.vayunmathur.library.map.ImageOverlay
import com.vayunmathur.library.map.MapOptions
import com.vayunmathur.library.map.OrnamentOptions
import com.vayunmathur.library.map.MapStyle
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.library.map.GeoBounds
import com.vayunmathur.library.map.GeoPoint

/**
 * Full-screen map that shades an area by a chosen weather [metric], decoded
 * natively from Open-Meteo's binary `.om` spatial files (keyless, model-native
 * resolution) via the Rust/JNI [OmTilesNative] bridge. The decoded field is
 * colorized to a bitmap and drawn as a translucent [RasterLayer] over a muted
 * raster basemap. Panning/zooming re-decodes only the visible region; the
 * measure and time step can be changed on the fly. Pre-set from the graph sheet
 * to a location + time + measure.
 */

/** Longest side (px) of the decoded/colorized overlay raster. */
private const val RASTER_MAX_DIM = 512

/**
 * Minimum camera zoom before the map resolves and shows the zoomed-in region's
 * local time. Below this the viewport spans multiple zones, so a single
 * region's time would be misleading. The initial camera zoom is 7 (≈ regional).
 */
private const val MIN_ZOOM_FOR_REGION_TZ = 7.0

/** A decoded + geolocated weather field ready to colorize into a bitmap. */
private data class DecodedRegion(
    val values: FloatArray,
    val width: Int,
    val height: Int,
    val bbox: GeoBounds,
)

/** Snapshot of the inputs that drive a decode; used to debounce scrubbing. */
private data class DecodeRequest(
    val bbox: GeoBounds?,
    val metric: WeatherMetric,
    val timeIndex: Int,
    val meta: OmMapMetadata?,
)

@OptIn(FlowPreview::class)
@Composable
fun WeatherMapPage(
    backStack: NavBackStack<Route>,
    latitude: Double,
    longitude: Double,
    name: String,
    isoTime: String?,
    metric: String,
) {
    val tempUnit = rememberTempUnit()
    val windUnit = rememberWindUnit()
    val pressureUnit = rememberPressureUnit()
    val use24Hour = rememberUse24Hour()

    val domain = DwdIconGlobal

    var selectedMetric by remember {
        mutableStateOf(
            runCatching { WeatherMetric.valueOf(metric) }
                .getOrDefault(WeatherMetric.Temperature)
                .takeIf { it in mapMetrics } ?: WeatherMetric.Temperature,
        )
    }

    // north-up, no tilt so the axis-aligned image quad stays correct.
    val camera = rememberCameraState(
        CameraPosition(target = GeoPoint(longitude, latitude), zoom = 7.0),
    )

    var metadata by remember { mutableStateOf<OmMapMetadata?>(null) }
    var timeIndex by remember { mutableStateOf(0) }
    var userScrubbed by remember { mutableStateOf(false) }
    var visibleBbox by remember { mutableStateOf<GeoBounds?>(null) }
    var overlay by remember { mutableStateOf<ImageBitmap?>(null) }
    var overlayBounds by remember { mutableStateOf<GeoBounds?>(null) }
    var loading by remember { mutableStateOf(false) }
    // The viewer's own zone: the primary time label is always shown in it.
    val userZone = remember { TimeZone.currentSystemDefault() }
    // The zone of the map center, resolved only when sufficiently zoomed in.
    var regionTz by remember { mutableStateOf<RegionTimezone?>(null) }
    // Cache region lookups by ~0.1° center so panning doesn't re-hit the API.
    val tzCache = remember { mutableMapOf<String, RegionTimezone>() }

    // Cache decoded regions by (rounded bbox, variable, valid_time) so panning
    // back and forth (or re-selecting a measure/time) doesn't re-fetch.
    val cache = remember { mutableMapOf<String, DecodedRegion>() }

    val supportedMetrics = remember(metadata) {
        metadata?.let { m -> mapMetrics.filter { m.supports(it) } } ?: mapMetrics
    }
    val times = metadata?.validTimes.orEmpty()

    // Load the model metadata once, then seed the time index near the requested time.
    LaunchedEffect(domain) {
        val meta = fetchOmMapMetadata(domain)
        metadata = meta
        android.util.Log.i(
            "OmMap",
            if (meta == null) "metadata NULL (fetch failed)"
            else "metadata ok ref=${meta.referenceTime} times=${meta.validTimes.size} vars=${meta.variables.size} native=${OmTilesNative.isAvailable}",
        )
        if (meta != null) {
            if (meta.validTimes.isNotEmpty() && !userScrubbed) {
                timeIndex = indexForIso(meta.validTimes, isoTime).coerceIn(0, meta.validTimes.size - 1)
            }
            // The incoming measure may not exist in this model run (e.g. UV,
            // dew point). Fall back to a supported one so the map isn't blank.
            if (!meta.supports(selectedMetric)) {
                val fallback = mapMetrics.firstOrNull { meta.supports(it) }
                if (fallback != null) selectedMetric = fallback
            }
        }
    }

    // Track the visible bounding box, debounced so we only decode once the
    // camera settles.
    LaunchedEffect(camera) {
        snapshotFlow { camera.position to camera.projection }
            .debounce(400)
            .collectLatest { (_, projection) ->
                visibleBbox = projection?.queryVisibleBoundingBox()
            }
    }

    // Resolve the map center's time zone when zoomed in enough, so the panel
    // can show the region's local time alongside the viewer's. Cleared (falls
    // back to viewer-only) when zoomed out past the threshold.
    LaunchedEffect(camera) {
        snapshotFlow { camera.position.target to camera.position.zoom }
            .debounce(500)
            .collectLatest { (target, zoom) ->
                if (zoom < MIN_ZOOM_FOR_REGION_TZ) {
                    regionTz = null
                    return@collectLatest
                }
                val key = "${(target.latitude * 10).roundToInt()},${(target.longitude * 10).roundToInt()}"
                tzCache[key]?.let {
                    regionTz = it
                    return@collectLatest
                }
                val tz = withContext(Dispatchers.IO) {
                    runCatching { WeatherApi.timezoneAt(target.latitude, target.longitude) }.getOrNull()
                }
                if (tz?.timezone != null) {
                    tzCache[key] = tz
                    regionTz = tz
                }
            }
    }

    // Decode + colorize when the region, measure, time step or run changes.
    // Debounced + collectLatest so dragging the time slider coalesces into a
    // single decode of the settled step instead of firing one per value.
    LaunchedEffect(Unit) {
        snapshotFlow { DecodeRequest(visibleBbox, selectedMetric, timeIndex, metadata) }
            .debounce(300)
            .collectLatest { req ->
                val bbox = req.bbox ?: return@collectLatest
                val meta = req.meta ?: return@collectLatest
                val validTime = meta.validTimes.getOrNull(req.timeIndex) ?: return@collectLatest
                if (!meta.supports(req.metric) || !OmTilesNative.isAvailable) {
                    android.util.Log.w(
                        "OmMap",
                        "skip decode: supports=${meta.supports(req.metric)} native=${OmTilesNative.isAvailable} metric=${req.metric}",
                    )
                    overlay = null
                    return@collectLatest
                }
                val variable = req.metric.omVariable
                val key = cacheKey(bbox, variable, validTime)

                val region = cache[key] ?: run {
                    loading = true
                    val decoded = withContext(Dispatchers.IO) {
                        val (w, h) = rasterSize(bbox)
                        val url = omFileUrl(domain, meta.referenceTime, validTime)
                        val t0 = System.currentTimeMillis()
                        // Range-fetching decodeRegion(url); ranges are fetched natively in Rust.
                        // (HttpURLConnection 64KB blocks, LRU) instead of full-file
                        // performRequestBytes (~148 MB) which caused OOM crash.
                        val values = OmTilesNative.decodeRegion(
                            url,
                            variable,
                            domain.nx, domain.ny, domain.lonMin, domain.latMin, domain.dx, domain.dy,
                            bbox.west, bbox.south, bbox.east, bbox.north,
                            w, h,
                        )
                        if (values == null) {
                            android.util.Log.w("OmMap", "decodeRegion null for $variable $validTime")
                            return@withContext null
                        }
                        android.util.Log.i(
                            "OmMap",
                            "decoded $variable $validTime ${w}x$h in ${System.currentTimeMillis() - t0}ms",
                        )
                        DecodedRegion(values, w, h, bbox)
                    }
                    loading = false
                    if (decoded != null) cache[key] = decoded
                    decoded
                }

                if (region != null) {
                    overlay = colorizeToBitmap(region.values, region.width, region.height, req.metric.colorRamp)
                    overlayBounds = region.bbox
                }
            }
    }

    val ramp = selectedMetric.colorRamp
    val valueFormatter = metricValueFormatter(selectedMetric, tempUnit, windUnit, pressureUnit)

    Box(modifier = Modifier.fillMaxSize()) {
        VectorMap(
            modifier = Modifier.fillMaxSize(),
            cameraState = camera,
            // Muted, so the colour-ramp overlay below reads over the basemap rather than
            // competing with it. This is what TileSource.CartoPositron was for.
            style = MapStyle.Muted,
            options = MapOptions(
                // Lock rotation/tilt so the north-up image quad stays aligned.
                gestureOptions = GestureOptions.RotationLocked,
                // Ornaments off. Note attribution is NOT an ornament any more: the map
                // renders it unconditionally, because it is a licence condition of the
                // OpenStreetMap data rather than a preference. Weather's own copy in the
                // bottom panel was removed rather than duplicated.
                ornamentOptions = OrnamentOptions.AllDisabled,
            ),
            imageOverlay = overlay?.let { bmp ->
                overlayBounds?.let { bounds -> ImageOverlay(bmp, bounds, 0.7f) }
            },
        )

        // Top bar: back + title + measure selector.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = { backStack.pop() }) {
                    IconBack()
                }
                MeasureSelector(
                    selected = selectedMetric,
                    options = supportedMetrics,
                    onSelect = { selectedMetric = it },
                )
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        // Bottom panel: time scrubber + legend.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shape = MaterialTheme.shapes.largeIncreased,
            tonalElevation = 3.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val currentIso = times.getOrNull(timeIndex)
                if (currentIso != null) {
                    Text(
                        text = formatInstantInZone(currentIso, userZone, use24Hour),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val region = regionTz
                    val regionZone = region?.timezone?.let {
                        runCatching { TimeZone.of(it) }.getOrNull()
                    }
                    if (regionZone != null && regionZone.id != userZone.id) {
                        val abbrev = region.abbreviation?.let { " · $it" }.orEmpty()
                        Text(
                            text = formatInstantInZone(currentIso, regionZone, use24Hour) + abbrev,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.map_time),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (times.size > 1) {
                    Slider(
                        value = timeIndex.toFloat(),
                        onValueChange = {
                            userScrubbed = true
                            timeIndex = it.roundToInt().coerceIn(0, times.size - 1)
                        },
                        valueRange = 0f..(times.size - 1).toFloat(),
                        steps = (times.size - 2).coerceAtLeast(0),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Legend(
                    metric = selectedMetric,
                    minLabel = valueFormatter(ramp.first().value),
                    maxLabel = valueFormatter(ramp.last().value),
                )
            }
        }
    }
}

@Composable
private fun MeasureSelector(
    selected: WeatherMetric,
    options: List<WeatherMetric>,
    onSelect: (WeatherMetric) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { expanded = true }) {
            Text(stringResource(selected.title))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { m ->
                DropdownMenuItem(
                    text = { Text(stringResource(m.title)) },
                    onClick = {
                        onSelect(m)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Legend(metric: WeatherMetric, minLabel: String, maxLabel: String) {
    val colors = metric.colorRamp.map { it.color }
    Column {
        Text(
            text = stringResource(metric.title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(Brush.horizontalGradient(colors), RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(minLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(maxLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Overlay raster size for [bbox], longest side [RASTER_MAX_DIM], aspect-matched. */
private fun rasterSize(bbox: GeoBounds): Pair<Int, Int> {
    val lonSpan = abs(bbox.east - bbox.west).coerceAtLeast(1e-6)
    val latSpan = abs(bbox.north - bbox.south).coerceAtLeast(1e-6)
    return if (lonSpan >= latSpan) {
        RASTER_MAX_DIM to (RASTER_MAX_DIM * latSpan / lonSpan).roundToInt().coerceIn(16, RASTER_MAX_DIM)
    } else {
        (RASTER_MAX_DIM * lonSpan / latSpan).roundToInt().coerceIn(16, RASTER_MAX_DIM) to RASTER_MAX_DIM
    }
}

/** Cache key: metric variable + valid time + bounds rounded to ~0.1°. */
private fun cacheKey(bbox: GeoBounds, variable: String, validTime: String): String {
    fun r(v: Double) = (v * 10).roundToInt()
    return "$variable@$validTime:${r(bbox.north)},${r(bbox.south)},${r(bbox.east)},${r(bbox.west)}"
}

/**
 * Index of the timestamp matching [isoTime] (by exact string, then by date+hour
 * prefix, then by date), or 0. Handles the trailing `Z` on `valid_times`.
 */
private fun indexForIso(times: List<String>, isoTime: String?): Int {
    if (times.isEmpty()) return 0
    if (isoTime != null) {
        val exact = times.indexOf(isoTime)
        if (exact >= 0) return exact
        val hourPrefix = isoTime.take(13) // yyyy-MM-ddTHH
        val byHour = times.indexOfFirst { it.take(13) == hourPrefix }
        if (byHour >= 0) return byHour
        val dayPrefix = isoTime.take(10)
        val byDay = times.indexOfFirst { it.take(10) == dayPrefix }
        if (byDay >= 0) return byDay
    }
    return 0
}
