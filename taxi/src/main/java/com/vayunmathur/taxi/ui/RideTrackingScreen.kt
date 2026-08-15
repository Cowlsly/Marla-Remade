package com.vayunmathur.taxi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.RasterMap
import com.vayunmathur.library.map.TileSource
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconMyLocation
import com.vayunmathur.library.ui.IconNavigationArrow
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.CancelResult
import com.vayunmathur.taxi.data.DriverLocation
import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.data.RideStatusResult
import com.vayunmathur.taxi.data.RideStopInfo
import com.vayunmathur.taxi.network.lyft.LyftProvider
import com.vayunmathur.taxi.notifications.RideTrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * The "Current ride" tab. Checks for an in-progress ride once; if there is one, shows live
 * tracking, otherwise a simple "no ride" message. Deliberately minimal state — no cross-tab
 * navigation or ride mutations happen here beyond the explicit Cancel button inside tracking.
 */
@Composable
fun CurrentRideScreen() {
    val context = LocalContext.current
    var rideId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val lyft = LyftProvider(context.applicationContext)
        rideId = if (lyft.isSignedIn()) {
            (lyft.activeRide() as? RideStatusResult.Active)?.ride?.rideId
        } else {
            null
        }
        loading = false
    }

    val id = rideId
    if (id != null) {
        RideTrackingScreen(rideId = id)
        return
    }

    AppScaffold(title = stringResource(R.string.nav_current_ride)) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    stringResource(R.string.no_current_ride),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Live tracking for one ride: the driver's position against the pickup and destination, the
 * current status, an ETA, and the driver/vehicle details — laid out as a full-bleed map with a
 * rounded card floating over the bottom (mirrors fooddelivery's OrderTrackingScreen).
 *
 * Polls the active ride (~5s) for status/ETA and the driver location (~3s) for smoother marker
 * movement, stopping once the ride is terminal. No navigation action cancels or ends the ride —
 * only the explicit Cancel button does.
 */
@Composable
fun RideTrackingScreen(rideId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val provider = remember { LyftProvider(context.applicationContext) }

    var ride by remember { mutableStateOf<ActiveRide?>(null) }
    var liveLocation by remember { mutableStateOf<DriverLocation?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var ended by remember { mutableStateOf(false) }
    var confirmingCancel by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var cancelMessage by remember { mutableStateOf<String?>(null) }

    fun isDone() = ended || ride?.status?.isTerminal == true

    // Active-ride poll: status, driver/vehicle details, per-stop ETA.
    LaunchedEffect(rideId) {
        // Keep the background Live Update tracker running for this ride too, so tracking
        // opened from the Current ride tab (or a re-opened app) is covered. Idempotent.
        RideTrackingService.start(context, rideId)
        while (true) {
            when (val result = provider.activeRide()) {
                is RideStatusResult.Active -> {
                    ride = result.ride
                    error = null
                }
                RideStatusResult.None -> {
                    // The active-ride endpoint may not surface a just-booked ride; fall back to
                    // reading it directly by id before giving up.
                    when (val byId = provider.rideById(rideId)) {
                        is RideStatusResult.Active -> {
                            ride = byId.ride
                            error = null
                        }
                        else -> ended = true
                    }
                }
                is RideStatusResult.Failed -> error = result.message
                RideStatusResult.Unsupported -> ended = true
            }
            if (isDone()) break
            delay(5_000)
        }
    }

    // Higher-frequency driver-location poll for smoother marker movement.
    LaunchedEffect(rideId) {
        while (true) {
            if (isDone()) break
            provider.driverLocation(rideId)?.let { liveLocation = it }
            delay(3_000)
        }
    }

    val driverLoc = liveLocation ?: ride?.driverLocation

    AppScaffold(title = stringResource(R.string.tracking_title)) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            TrackingMap(ride, driverLoc, Modifier.fillMaxSize())

            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                tonalElevation = 3.dp,
                shadowElevation = 12.dp,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                        .padding(bottom = padding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val current = ride
                    when {
                        current != null -> {
                            StatusHeader(current, ended)
                            DriverCard(
                                current,
                                onCall = { phone -> ExternalIntents.dial(context, phone) },
                            )
                            if (!isDone()) {
                                Button(
                                    onClick = { confirmingCancel = true },
                                    enabled = !cancelling,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    if (cancelling) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.cancelling))
                                    } else {
                                        Text(stringResource(R.string.cancel_ride))
                                    }
                                }
                            }
                        }

                        error != null ->
                            Text(
                                stringResource(R.string.tracking_error, error!!),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )

                        else ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(R.string.tracking_loading))
                            }
                    }
                }
            }
        }
    }

    if (confirmingCancel) {
        AlertDialog(
            onDismissRequest = { confirmingCancel = false },
            title = { Text(stringResource(R.string.cancel_ride_title)) },
            text = { Text(stringResource(R.string.cancel_ride_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingCancel = false
                    cancelling = true
                    scope.launch {
                        cancelMessage = when (val result = provider.cancelRide(rideId)) {
                            is CancelResult.Done -> context.getString(R.string.ride_cancelled)
                            is CancelResult.Failed -> context.getString(R.string.cancel_failed, result.message)
                            CancelResult.Unsupported ->
                                context.getString(R.string.cancel_failed, "unsupported")
                        }
                        cancelling = false
                        ended = true
                    }
                }) {
                    Text(stringResource(R.string.cancel_ride), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingCancel = false }) {
                    Text(stringResource(R.string.keep_ride))
                }
            },
        )
    }

    cancelMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { cancelMessage = null },
            title = { Text(message) },
            confirmButton = {
                TextButton(onClick = { cancelMessage = null }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

/**
 * Pickup, destination and the live driver on a raster map. Markers are absolutely positioned from
 * the camera projection; the view auto-frames whatever points are known.
 */
@Composable
private fun TrackingMap(ride: ActiveRide?, driverLoc: DriverLocation?, modifier: Modifier = Modifier) {
    val stops = ride?.stops ?: emptyList()
    val pickup = stops.firstOrNull { it.isPickup }?.geoPoint() ?: stops.firstOrNull()?.geoPoint()
    val dropoff = stops.firstOrNull { it.isDropoff }?.geoPoint() ?: stops.lastOrNull()?.geoPoint()
    val driver = driverLoc?.let { GeoPoint(it.longitude, it.latitude) }
    val points = listOfNotNull(pickup, dropoff, driver)

    if (points.isEmpty()) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        return
    }

    val centre = GeoPoint(
        longitude = points.sumOf { it.longitude } / points.size,
        latitude = points.sumOf { it.latitude } / points.size,
    )
    val spread = max(
        points.maxOf { it.longitude } - points.minOf { it.longitude },
        points.maxOf { it.latitude } - points.minOf { it.latitude },
    )
    val zoom = when {
        spread <= 0.0 -> 15.0
        else -> min(16.0, max(10.0, Math.log(360.0 / spread) / Math.log(2.0)))
    }
    val camera = rememberCameraState(CameraPosition(target = centre, zoom = zoom))
    LaunchedEffect(centre, zoom) {
        camera.position = CameraPosition(target = centre, zoom = zoom)
    }

    Box(modifier) {
        RasterMap(cameraState = camera, tileSource = TileSource.CartoVoyager)
        val projection = camera.projection
        if (projection != null) {
            pickup?.let {
                MapPin(projection.screenLocationFromPosition(it), MaterialTheme.colorScheme.tertiary) {
                    IconMyLocation(tint = Color.White)
                }
            }
            dropoff?.let {
                MapPin(projection.screenLocationFromPosition(it), MaterialTheme.colorScheme.primary) {
                    IconHome(tint = Color.White)
                }
            }
            driver?.let {
                MapPin(projection.screenLocationFromPosition(it), MaterialTheme.colorScheme.secondary) {
                    IconNavigationArrow(
                        tint = Color.White,
                        modifier = Modifier.rotate((driverLoc.bearing ?: 0.0).toFloat()),
                    )
                }
            }
        }
    }
}

private fun RideStopInfo.geoPoint(): GeoPoint? =
    location?.let { GeoPoint(it.longitude, it.latitude) }

/** A circular marker centred on [at] (a viewport offset from the camera projection). */
@Composable
private fun MapPin(at: DpOffset, color: Color, icon: @Composable () -> Unit) {
    val size = 32.dp
    Box(Modifier.offset(at.x - size / 2, at.y - size / 2)) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(size)) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
    }
}

@Composable
private fun StatusHeader(ride: ActiveRide, ended: Boolean) {
    val over = ended || ride.status.isTerminal
    Column {
        Text(
            stringResource(statusLabel(ride.status, ended)),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        if (!over) {
            val etaSeconds = ride.pickupEtaSeconds
            val etaText = when {
                ride.status == RideStatus.ARRIVED -> stringResource(R.string.status_arrived)
                ride.status == RideStatus.PICKED_UP -> stringResource(R.string.status_on_trip)
                etaSeconds != null && etaSeconds > 0 ->
                    stringResource(R.string.eta_minutes, (etaSeconds + 59) / 60)
                ride.status.isPrePickup -> stringResource(R.string.driver_arriving_now)
                else -> null
            }
            if (etaText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    etaText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { statusProgress(ride.status, over) },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun DriverCard(ride: ActiveRide, onCall: (String) -> Unit) {
    val driver = ride.driver
    val vehicle = ride.vehicle
    if (driver == null && vehicle == null) return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val image = driver?.imageUrl?.takeIf { it.isNotBlank() }
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = driver.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(CircleShape),
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = CircleShape,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) { IconPerson() }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.your_driver),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val name = driver?.displayName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.driver_label)
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    driver?.rating?.let { rating ->
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconStar(Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "%.1f".format(rating),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                driver?.phoneNumber?.takeIf { it.isNotBlank() }?.let { phone ->
                    OutlinedButton(onClick = { onCall(phone) }) {
                        Text(stringResource(R.string.call))
                    }
                }
            }

            vehicle?.let { v ->
                val desc = v.description
                if (desc.isNotBlank() || v.licensePlate != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (desc.isNotBlank()) {
                                Text(desc, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        v.licensePlate?.let { plate ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    plate,
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: RideStatus, ended: Boolean): Int = when {
    ended && status != RideStatus.CANCELED && !status.isTerminal -> R.string.ride_ended
    status == RideStatus.CANCELED -> R.string.ride_ended
    status.isTerminal -> R.string.ride_complete
    status == RideStatus.ARRIVED -> R.string.status_arrived
    status == RideStatus.PICKED_UP -> R.string.status_on_trip
    status == RideStatus.ACCEPTED || status == RideStatus.APPROACHING -> R.string.status_en_route
    else -> R.string.status_finding_driver
}

private fun statusProgress(status: RideStatus, over: Boolean): Float = when {
    over -> 1f
    status == RideStatus.PENDING -> 0.15f
    status == RideStatus.ACCEPTED -> 0.35f
    status == RideStatus.APPROACHING -> 0.55f
    status == RideStatus.ARRIVED -> 0.7f
    status == RideStatus.PICKED_UP -> 0.9f
    else -> 0.1f
}
