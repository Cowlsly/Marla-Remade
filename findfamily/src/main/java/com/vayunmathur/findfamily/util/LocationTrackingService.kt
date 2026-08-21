package com.vayunmathur.findfamily.util
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.math.sqrt
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.havershine
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.findfamily.uwb.UwbEnvelopeKind
import com.vayunmathur.findfamily.uwb.UwbInbox
import com.vayunmathur.findfamily.BuildConfig
import com.vayunmathur.findfamily.data.UserKind
import com.vayunmathur.findfamily.tracker.TrackerBeaconScanner
import com.vayunmathur.findfamily.tracker.TrackerReporting
import com.vayunmathur.findfamily.tracker.TrackerStore
import com.vayunmathur.findfamily.MainActivity
import com.vayunmathur.findfamily.R
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.work.startRepeatedTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LocationTrackingService : Service(), SensorEventListener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private var accelerometer: Sensor? = null
    private var significantMotionSensor: Sensor? = null

    private val triggerEventListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            isMoving = true
            lastMovementTime = System.currentTimeMillis()
            serviceScope.launch(Dispatchers.Main) {
                setupLocationUpdates()
            }
            // Start monitoring for stillness
            accelerometer?.let {
                sensorManager.registerListener(this@LocationTrackingService, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    private val repository by lazy { FindFamilyRepository.get(this) }
    private lateinit var bm: BatteryManager
    
    private var isGpsRunning = false
    private var isMoving = false
    private var lastMovementTime = 0L
    private var lastKnownLocation: Location? = null
    private var heartbeatJob: Job? = null
    private var trackingInitialized = false

    // Custom UWB tracker feature (DEV_BUILD only). Owner-side store of tracker
    // secrets/private keys, and the finder-side beacon scan job.
    private var trackerStore: TrackerStore? = null
    private var trackerScanJob: Job? = null

    private val networkListener = LocationListener { location ->
        lastKnownLocation = location
        if (location.accuracy > 100f) {
            if (!isGpsRunning && isMoving) startGps()
        } else {
            if (isGpsRunning) stopGps()
        }
    }
    private val gpsListener = LocationListener { location ->
        lastKnownLocation = location
    }

    private suspend fun syncHeartbeat() {
        val location = lastKnownLocation ?: run {
            Log.d("FF-Heartbeat", "syncHeartbeat: no lastKnownLocation yet")
            return
        }
        if (Networking.userid == 0L) {
            Log.d("FF-Heartbeat", "syncHeartbeat: userid==0, not initialized yet")
            return
        }

        // Shield the entire heartbeat so one failing DAO / crypto / network call
        // does not kill the foreground service loop (which previously surfaced as
        // FATAL BadPaddingException in decrypt).
        try {
            val currentUsers = repository.getAllUsers()
            val currentLinks = repository.getAllTemporaryLinks()
            val now = Clock.System.now()

            Log.d("FF-Heartbeat", "heartbeat userid=${Networking.userid.toULong()} self raw=${Networking.userid} users=${currentUsers.size} links=${currentLinks.size} moving=$isMoving loc=${location.latitude},${location.longitude} acc=${location.accuracy}")

            val locationValue = LocationValue(
                Networking.userid,
                Coord(location.latitude, location.longitude),
                0f,
                location.accuracy,
                now,
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
            )

            Log.d("FF-Heartbeat", "upsert local LocationValue for self")
            repository.upsertLocation(locationValue)

            if (currentUsers.none { it.id == Networking.userid }) {
                Log.d("FF-Heartbeat", "self not in user DB, inserting me")
                repository.upsertUser(
                    User(
                        getString(R.string.me_label),
                        null,
                        "Unnamed Location",
                        true,
                        RequestStatus.MUTUAL_CONNECTION,
                        Clock.System.now(),
                        null,
                        Networking.userid
                    )
                )
            }

            // Auto-toggle check: atomic flip guarded by the timer value itself.
            // If the user manually cleared or rescheduled after we read currentUsers, the
            // WHERE clause (sharingAutoToggleAt <= now) won't match and we won't accidentally
            // disable/enable when they didn't intend it. No stale copy() + upsert().
            var publishBaseUsers = currentUsers
            try {
                val flipped = repository.applyDueAutoToggles(now.epochSeconds)
                if (flipped > 0) {
                    Log.d("FF-Heartbeat", "auto-toggle flipped $flipped user(s), reloading sharing state before publish")
                    // Reload fresh sharing flags so we don't publish once after an intended disable,
                    // and we start publishing immediately after an intended enable.
                    publishBaseUsers = repository.getAllUsers()
                }
            } catch (e: Exception) {
                Log.w("FF-Heartbeat", "auto-toggle apply failed", e)
            }

            // Arrival auto-toggle check (GitHub #406): flip sharing for any user whose trigger
            // points at a saved place that "Me" is currently inside. Uses the same atomic,
            // waypoint-id-guarded update as the timer path so a stale snapshot cannot mis-flip.
            try {
                val myCoord = Coord(location.latitude, location.longitude)
                val insideWaypointIds = repository.getAllWaypoints()
                    .filter { havershine(it.coord, myCoord) < it.range }
                    .map { it.id }
                if (insideWaypointIds.isNotEmpty()) {
                    val flippedArrival = repository.applyDueArrivalToggles(insideWaypointIds)
                    if (flippedArrival > 0) {
                        Log.d("FF-Heartbeat", "arrival auto-toggle flipped $flippedArrival user(s), reloading sharing state before publish")
                        publishBaseUsers = repository.getAllUsers()
                    }
                }
            } catch (e: Exception) {
                Log.w("FF-Heartbeat", "arrival auto-toggle apply failed", e)
            }

            val publishTargets = publishBaseUsers.filter { it.id != Networking.userid && it.sendingEnabled }
            Log.d("FF-Heartbeat", "publish targets count=${publishTargets.size} ids=${publishTargets.map{ it.id.toULong() }} names=${publishTargets.map{ it.name }}")
            publishTargets.forEach {
                val result = runCatching { Networking.publishLocation(locationValue, it) }
                if (result.isFailure) Log.w("FF-Heartbeat", "publish to ${it.id.toULong()} threw", result.exceptionOrNull())
            }
            currentLinks.filter { now < it.deleteAt }.forEach {
                val result = runCatching { Networking.publishLocation(locationValue, it) }
                if (result.isFailure) Log.w("FF-Heartbeat", "publish to link ${it.id} threw", result.exceptionOrNull())
            }
            currentLinks.filter { now >= it.deleteAt }.forEach { runCatching { repository.deleteTemporaryLink(it) } }

            // Incoming peer locations arrive via the live WebSocket push (see startTracking →
            // Networking.startLive). There is no HTTP receive; if the socket is down the loop
            // reconnects and the next heartbeat re-publishes.
        } catch (e: Exception) {
            Log.w("FF-Heartbeat", "syncHeartbeat crashed", e)
        }
    }

    /**
     * Processes a batch of freshly-decrypted peer locations: inserts unknown
     * senders, recomputes waypoint entry/exit + low-battery notifications, and
     * upserts the values. Self-contained (re-reads users/waypoints) so it can be
     * driven by both the 30s heartbeat poll and the live WebSocket push.
     */
    private suspend fun processIncomingLocations(locList: List<LocationValue>) {
        if (locList.isEmpty()) return
        val currentUsers = repository.getAllUsers()
        val currentWaypoints = repository.getAllWaypoints()
        val userIDs = currentUsers.map { it.id }

        val usersRecieved = locList.map { it.userid }.distinct()
        Log.d("FF-Heartbeat", "received userids=${usersRecieved.map{ it.toULong() }} self=${Networking.userid.toULong()} known=${userIDs.map{ it.toULong() }}")
        val newUsers = usersRecieved.filter { it !in userIDs && it != Networking.userid }
        Log.d("FF-Heartbeat", "newUsers to insert=${newUsers.map{ it.toULong() }}")
        repository.insertUsersIgnore(newUsers.map {
            User(" ", null, "Unknown Location", false, RequestStatus.AWAITING_REQUEST, Clock.System.now(), null, it)
        })

        // Snapshot the previous latest-per-user BEFORE persisting the new fixes, so the
        // battery-threshold and self-waypoint comparisons below still see the prior state
        // rather than the fix we're about to store.
        val latestMap = repository.latestLocationsOnce().associateBy { it.userid }

        // Persist the raw fixes immediately, before any enrichment. Everything after this
        // (waypoint detection, reverse-geocoding via fetchAddress, notifications) is
        // best-effort: the geocoder is a slow network call, any of it can throw, and the
        // whole delivery is cancelled when the live socket reconnects mid-batch (the caller
        // also swallows exceptions). Persisting last meant a slow/failed/cancelled
        // enrichment step silently dropped the location, so getLatest() kept serving stale
        // fixes even across a force-stop. Writing here makes the fix durable no matter what
        // follows.
        repository.upsertLocations(locList)
        Log.d("FF-Heartbeat", "upsertAll ${locList.size} locations done")

        currentUsers.forEach { user ->
            // Self never receives its own published location, so fall back to the latest
            // stored fix; otherwise "me" never gets its waypoint recomputed.
            val lastLoc = if (user.id == Networking.userid) latestMap[Networking.userid]
            else locList.filter { it.userid == user.id }.maxByOrNull { it.timestamp }
            lastLoc ?: return@forEach
            val lastSavedLoc = latestMap[user.id]

            if (lastLoc.battery <= 15f && (lastSavedLoc?.battery ?: 100f) > 15f) {
                if (user.id != Networking.userid) {
                    createNotificationWithCategory(user.name, getString(R.string.notification_low_battery, user.name), "BATTERY_LOW", user.id)
                }
            }

            val inWaypoint = currentWaypoints.find { havershine(it.coord, lastLoc.coord) < it.range }
            val prevId = user.lastWaypointId
            val stillInsidePrev = prevId?.let { pid ->
                currentWaypoints.find { it.id == pid }?.let {
                    havershine(it.coord, lastLoc.coord) < it.range * 1.2
                }
            } ?: false
            val currentId: Long? = inWaypoint?.id ?: if (stillInsidePrev) prevId else null

            // Display name: prefer waypoint name (either entered or sticky-via-hysteresis), then geocoded address.
            val displayName = inWaypoint?.name
                ?: currentWaypoints.find { it.id == currentId }?.name
                ?: runCatching { fetchAddress(lastLoc.coord.lat, lastLoc.coord.lon) }.getOrNull()?.let {
                    it.featureName ?: it.thoroughfare
                }
                ?: "Unknown Location"

            if (currentId != prevId || displayName != user.locationName) {
                // Atomic partial update — avoids stale snapshot via copy() + upsert()
                // clobbering sharingAutoToggleAt / sendingEnabled and accidentally
                // disabling sharing when you didn't intend it.
                repository.updateLocationMeta(
                    id = user.id,
                    locationName = displayName,
                    lastWaypointId = currentId,
                    lastLocationChangeTime = lastLoc.timestamp.epochSeconds
                )
            }

            if (currentId != prevId && user.id != Networking.userid) {
                if (currentId != null) {
                    val enteredName = inWaypoint?.name
                        ?: currentWaypoints.find { it.id == currentId }?.name
                        ?: displayName
                    createNotificationWithCategory(user.name, getString(R.string.notification_entered_waypoint, user.name, enteredName), "ENTRY_EXIT", user.id)
                } else if (prevId != null) {
                    val exitedName = currentWaypoints.find { it.id == prevId }?.name ?: user.locationName
                    createNotificationWithCategory(user.name, getString(R.string.notification_exited_waypoint, user.name, exitedName), "ENTRY_EXIT", user.id)
                }
            }
        }
    }

    /**
     * Forwards decrypted UWB envelopes to [UwbInbox] and fires a local
     * notification for REQUEST envelopes. Driven by the live WebSocket push.
     */
    private suspend fun handleUwbEnvelopes(list: List<UwbEnvelope>) {
        if (list.isEmpty()) return
        val users = repository.getAllUsers()
        for (envelope in list) {
            UwbInbox.tryEmit(envelope)
            if (envelope.kind == UwbEnvelopeKind.REQUEST) {
                val senderId = envelope.sender.toLong()
                val senderName = users.firstOrNull { it.id == senderId }?.name
                    ?: getString(R.string.uwb_unknown_peer_name)
                createUwbRequestNotification(senderName, senderId)
            }
        }
    }

    // -----------------------------------------------------------------
    // Custom UWB tracker crowd-finding (DEV_BUILD only)
    // -----------------------------------------------------------------

    /**
     * Finder path: subscribe to tracker beacon sightings and, for each, upload a
     * report stamped with this device's current GPS (if accurate enough). The sealed
     * report is readable only by the tracker's owner. Started once from startTracking.
     */
    private fun startTrackerScanner() {
        if (trackerScanJob?.isActive == true) return
        trackerScanJob = serviceScope.launch {
            runCatching {
                TrackerBeaconScanner(this@LocationTrackingService).sightings().collect { sighting ->
                    val loc = lastKnownLocation
                    if (loc == null) {
                        // Both of these drops used to be silent, which made a stalled
                        // crowd-finding pipeline indistinguishable from one that was
                        // never hearing the beacon at all.
                        Log.i("FF-Tracker", "sighting dropped: no location fix yet")
                        return@collect
                    }
                    if (loc.accuracy > 100f) {
                        Log.i("FF-Tracker", "sighting dropped: accuracy ${loc.accuracy}m > 100m")
                        return@collect
                    }
                    val battery = runCatching {
                        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
                    }.getOrDefault(0f)
                    val lv = LocationValue(
                        Networking.userid,
                        Coord(loc.latitude, loc.longitude),
                        0f,
                        loc.accuracy,
                        Clock.System.now(),
                        battery,
                    )
                    runCatching { TrackerReporting.reportSighting(sighting, lv) }
                        .onSuccess { if (!it) Log.i("FF-Tracker", "reportSighting returned false (epoch id unresolved or socket down)") }
                        .onFailure { Log.w("FF-Tracker", "reportSighting failed", it) }
                }
            }.onFailure { Log.w("FF-Tracker", "tracker scan collect failed", it) }
        }
    }

    /**
     * Owner path: (re)register owned trackers so finders can resolve them, then fetch
     * and decrypt recent crowd reports and feed them through the normal incoming
     * pipeline so each tracker shows up as a map pin. Runs on the heartbeat tick.
     */
    private suspend fun pollTrackerReports() {
        val store = trackerStore ?: return
        val trackers = runCatching { repository.getAllUsers().filter { it.kind == UserKind.TRACKER } }
            .getOrDefault(emptyList())
        if (trackers.isEmpty()) return
        val locs = ArrayList<LocationValue>()
        for (t in trackers) {
            runCatching { TrackerReporting.registerTracker(t, store) }
            locs += runCatching { TrackerReporting.fetchTrackerLocations(t, store) }
                .getOrDefault(emptyList())
        }
        if (locs.isNotEmpty()) processIncomingLocations(locs)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val accel = sqrt(x*x + y*y + z*z)
            if (accel > 0.5f) {
                lastMovementTime = System.currentTimeMillis()
                if (!isMoving) {
                    isMoving = true
                    setupLocationUpdates()
                }
            } else {
                if (isMoving && (System.currentTimeMillis() - lastMovementTime > 60_000L)) {
                    isMoving = false
                    stopTrackingUpdates()
                    if (significantMotionSensor != null) {
                        sensorManager.unregisterListener(this, accelerometer)
                        requestSignificantMotion()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        setupNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Defensive: never run location tracking without fine-location permission.
        // The service can be (re)started by the OS, WorkManager, or BootReceiver,
        // and the permission may have been revoked since it was scheduled
        // (e.g. "Only this time" grant expiring, or the user switching to
        // approximate-only / "Ask every time" / "Don't allow").
        if (!LocationServiceController.hasFineLocationPermission(this)) {
            // We were started via startForegroundService and must satisfy the
            // foreground-start contract. Use the type-less startForeground so it
            // doesn't throw without the location permission, then stop.
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (_: Exception) {
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()

        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        startTracking()
        return START_STICKY
    }

    private fun startTracking() {
        serviceScope.launch {
            if (!trackingInitialized) {
                Networking.init(repository, DataStoreUtils.getInstance(this@LocationTrackingService), getString(R.string.me_label))

                // Hoist the UWB ranging session into this foreground service
                // so we can auto-accept incoming Find Nearby (UWB) requests
                // (and keep the session alive) without the user having to
                // bring the app to foreground first. See UwbSessionManager.
                UwbSessionManager.init(this@LocationTrackingService, repository)

                // Custom UWB tracker crowd-finding (DEV_BUILD): owner-side secret/key
                // store. Gated so release builds never touch it.
                if (BuildConfig.DEV_BUILD) {
                    trackerStore = TrackerStore(DataStoreUtils.getInstance(this@LocationTrackingService))
                }

                withContext(Dispatchers.Main) {
                    registerSensors()
                    // If we don't have any recent location (e.g. fresh start or recovery
                    // from a crash), force isMoving = true so setupLocationUpdates()
                    // immediately starts requesting GPS instead of waiting for the
                    // significant-motion sensor to trigger.
                    if (lastKnownLocation == null) {
                        isMoving = true
                        lastMovementTime = System.currentTimeMillis()
                    }
                    setupLocationUpdates()
                }
                trackingInitialized = true
            }

            // Cancel any prior heartbeat coroutine so onStartCommand re-entries
            // (e.g. from ServiceRestartWorker) don't stack multiple heartbeat loops.
            heartbeatJob?.cancel()
            heartbeatJob = launch {
                while (isActive) {
                    // "Only this time" grants are revoked once the app leaves the
                    // foreground; detect that here and shut down gracefully rather
                    // than spinning (or crashing) on location access we can't make.
                    if (!LocationServiceController.hasFineLocationPermission(this@LocationTrackingService)) {
                        withContext(Dispatchers.Main) { stopSelf() }
                        break
                    }
                    syncHeartbeat()
                    if (BuildConfig.DEV_BUILD) runCatching { pollTrackerReports() }
                    delay(30.seconds)
                }
            }

            // Live relay: the server pushes peer locations and UWB envelopes over the
            // WebSocket the instant they arrive, driving the same processing paths the
            // heartbeat used to. This is the only inbound path — there is no HTTP poll.
            Networking.startLive(
                serviceScope,
                onLocations = { processIncomingLocations(it) },
                onUwb = { handleUwbEnvelopes(it) },
            )

            // Finder side of the crowd-finding network: scan for tracker beacons and
            // report each sighting with our own GPS. DEV_BUILD only.
            if (BuildConfig.DEV_BUILD) startTrackerScanner()
        }
    }

    private fun registerSensors() {
        bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        significantMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

        if (significantMotionSensor != null) {
            requestSignificantMotion()
        } else {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun requestSignificantMotion() {
        significantMotionSensor?.let {
            sensorManager.requestTriggerSensor(triggerEventListener, it)
        }
    }

    private fun setupLocationUpdates() {
        if (!isMoving) return
        val isLowPower = powerManager.isPowerSaveMode
        val networkInterval = if (isLowPower) 30_000L else 10_000L

        // Devices without Play Services / MicroG (e.g. GrapheneOS) may have no
        // NETWORK_PROVIDER at all. Requesting updates from a missing provider
        // throws IllegalArgumentException, which used to crash the app on every
        // launch. Guard the request the same way startGps() guards GPS, and when
        // network location is unavailable fall back to GPS-only tracking (the
        // networkListener that would normally start GPS never fires without a
        // network provider).
        val hasNetworkProvider =
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (hasNetworkProvider) {
            LocationProviderStatus.setUsingGpsFallback(false)
            try {
                locationManager.removeUpdates(networkListener)
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    networkInterval,
                    0f,
                    networkListener
                )
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        } else {
            LocationProviderStatus.setUsingGpsFallback(true)
            startGps()
        }
    }

    private fun stopTrackingUpdates() {
        locationManager.removeUpdates(networkListener)
        stopGps()
    }

    companion object {
        private const val CHANNEL_ID = "location_tracking_channel"
        private const val BATTERY_CHANNEL_ID = "battery_channel"
        private const val ENTRY_EXIT_CHANNEL_ID = "entry_exit_channel"
        private const val UWB_REQUEST_CHANNEL_ID = "uwb_request_channel"
        private const val NOTIFICATION_ID = 101
    }

    private fun setupNotificationChannels() {
        // 1. Create the Channel (Required for API 26+)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_location_tracking_name),
            NotificationManager.IMPORTANCE_LOW // Low importance so it doesn't "pop up" or make noise
        ).apply {
            description = getString(R.string.notification_channel_location_tracking_desc)
        }

        // 2. Battery Alerts Channel (High Importance for visibility)
        val batteryChannel = NotificationChannel(
            BATTERY_CHANNEL_ID,
            getString(R.string.notification_channel_battery_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_battery_desc)
        }

        // 3. Entry/Exit Channel
        val arrivalChannel = NotificationChannel(
            ENTRY_EXIT_CHANNEL_ID,
            getString(R.string.notification_channel_entry_exit_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_entry_exit_desc)
        }

        // 4. UWB Find Nearby (UWB) Request Channel
        val uwbChannel = NotificationChannel(
            UWB_REQUEST_CHANNEL_ID,
            getString(R.string.notification_channel_uwb_request_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_uwb_request_desc)
        }

        // Register all channels
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannels(listOf(channel, batteryChannel, arrivalChannel, uwbChannel))
    }

    private fun createNotification(): Notification {
        // Create an Intent to open the app when the notification is clicked
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE // Required for API 31+
        )

        // 3. Build the notification
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_tracking_title))
            .setContentText(getString(R.string.notification_tracking_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure this exists in your res/drawable
            .setOngoing(true) // Makes it persistent
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) // API 31+ specific
            .build()
    }

    private fun createNotificationWithCategory(title: String, message: String, category: String, userId: Long) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channelId = when (category) {
            "BATTERY_LOW" -> BATTERY_CHANNEL_ID
            "ENTRY_EXIT" -> ENTRY_EXIT_CHANNEL_ID
            else -> CHANNEL_ID
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Consider using specific icons for battery/location
            .setAutoCancel(true)
            .build()

        // Stable per-(user, category) ID so repeat notifications replace rather than stack.
        val notificationId = "$userId::$category".hashCode()
        manager.notify(notificationId, notification)
    }

    /**
     * Notification fired when an incoming UWB Find Nearby (UWB) request arrives
     * via the heartbeat. Tapping it opens MainActivity with a deep link to the
     * ranging screen for the requesting user.
     */
    private fun createUwbRequestNotification(senderName: String, senderId: Long) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_UWB_PEER_ID, senderId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, senderId.hashCode(), openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, UWB_REQUEST_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_uwb_request_title))
            .setContentText(getString(R.string.notification_uwb_request_text, senderName))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        manager.notify("$senderId::UWB_REQUEST".hashCode(), n)
    }

    private fun startGps() {
        if (!isMoving) return
        val isLowPower = powerManager.isPowerSaveMode
        val gpsInterval = if (isLowPower) 180_000L else 60_000L

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.removeUpdates(gpsListener)
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    gpsInterval,
                    0f,
                    gpsListener
                )
                isGpsRunning = true
            }
        } catch (_: SecurityException) {
        }
    }

    private fun stopGps() {
        locationManager.removeUpdates(gpsListener)
        isGpsRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Networking.stopLive()
        // These are only initialized once startTracking()/registerSensors() runs.
        // The service can be destroyed before that (e.g. stopped immediately in
        // onStartCommand when permission is missing), so guard every access.
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(this)
            significantMotionSensor?.let {
                sensorManager.cancelTriggerSensor(triggerEventListener, it)
            }
        }
        if (::locationManager.isInitialized) {
            stopTrackingUpdates()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}

suspend fun Context.fetchAddress(lat: Double, lng: Double): Address? =
    suspendCancellableCoroutine { continuation ->
        val geocoder = Geocoder(this, Locale.getDefault())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Modern Async API (Android 13+)
            geocoder.getFromLocation(lat, lng, 1) { addresses ->
                val result = addresses.firstOrNull()

                // Use the stable 3-parameter lambda
                continuation.resume(result) { _, _, _ ->
                    /* No specific cleanup needed for Address objects */
                }
            }
        } else {
            // Legacy Synchronous (Must be on background thread)
            try {
                @Suppress("DEPRECATION")
                val address = geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
                continuation.resume(address)
            } catch (_: Exception) {
                continuation.resume(null)
            }
        }

        // Safety: If the calling scope is canceled, stop the continuation
        continuation.invokeOnCancellation {
            // Geocoder doesn't support manual cancellation,
            // but this prevents memory leaks in the listener.
        }
    }

class ServiceRestartWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        // Reconcile the service with the current state: only run when fine
        // location is granted AND the user is sharing with at least one person.
        LocationServiceController.syncServiceState(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

/**
 * Single source of truth for whether the [LocationTrackingService] should be
 * running and for (re)starting / stopping it accordingly.
 *
 * The service runs whenever fine (precise) location permission is granted **and**
 * the user hasn't turned tracking off (the [TRACKING_ENABLED_KEY] flag, toggled
 * from the Quick Settings tile — see TrackingTileService). Sharing toggles are
 * enforced inside the heartbeat (we only publish to users with sendingEnabled=true)
 * rather than by stopping the service, so that UWB inbox draining, waypoint
 * entry/exit, low-battery alerts, and receiving peers' locations continue to work
 * even when the user pauses sharing or on fresh install before any contact is added.
 */
object LocationServiceController {

    /** Persisted on/off switch for the whole tracking service (default on). */
    const val TRACKING_ENABLED_KEY = "tracking_enabled"

    fun hasFineLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /** Whether the user has left tracking enabled. Defaults to true (opt-out, not opt-in). */
    suspend fun isTrackingEnabled(context: Context): Boolean =
        DataStoreUtils.getInstance(context).getBooleanAwait(TRACKING_ENABLED_KEY, true)

    /** Persist the tracking on/off choice and immediately start/stop the service to match. */
    suspend fun setTrackingEnabled(context: Context, enabled: Boolean) {
        DataStoreUtils.getInstance(context).setBoolean(TRACKING_ENABLED_KEY, enabled)
        syncServiceState(context)
    }

    /**
     * True iff the user is sharing their location with at least one *other*
     * person (the self user is excluded). Reads directly from the DB so the
     * answer is correct regardless of whether the UI/ViewModel is alive.
     */
    suspend fun isSharingEnabled(context: Context): Boolean {
        val ds = DataStoreUtils.getInstance(context)
        val selfId = try { ds.getLongAwait("userid") } catch (_: Exception) { ds.getLong("userid") }
        return FindFamilyRepository.get(context).getAllUsers().any { it.sendingEnabled && it.id != selfId }
    }

    /**
     * Start the service if eligible, otherwise make sure it is stopped. Safe to
     * call from any context (worker, boot, ViewModel, permission refresh, tile).
     * Eligible = fine-location permission granted AND tracking not turned off.
     */
    suspend fun syncServiceState(context: Context) {
        val appContext = context.applicationContext
        val eligible = hasFineLocationPermission(appContext) && isTrackingEnabled(appContext)
        val intent = Intent(appContext, LocationTrackingService::class.java)
        withContext(Dispatchers.Main) {
            if (eligible) {
                try {
                    appContext.startForegroundService(intent)
                } catch (_: Exception) {
                }
            } else {
                appContext.stopService(intent)
            }
        }
    }

    /** Unconditionally stop the service. */
    fun stop(context: Context) {
        context.applicationContext.stopService(
            Intent(context.applicationContext, LocationTrackingService::class.java)
        )
    }
}

fun ensureSync(context: Context) {
    startRepeatedTask<ServiceRestartWorker>(
        context, "Location Sync", 15.minutes,
        ExistingWorkPolicy.REPLACE, ExistingPeriodicWorkPolicy.REPLACE
    )
}