package com.vayunmathur.findfamily.util
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.UserManager
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
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.havershine
import com.vayunmathur.findfamily.platform.FinalLocationReporter
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.findfamily.uwb.UwbEnvelopeKind
import com.vayunmathur.findfamily.uwb.UwbInbox
import com.vayunmathur.findfamily.BuildConfig
import com.vayunmathur.findfamily.data.UserKind
import com.vayunmathur.findfamily.tracker.PoweredOffKeyStore
import com.vayunmathur.findfamily.tracker.PoweredOffReporting
import com.vayunmathur.findfamily.tracker.PoweredOffScanner
import com.vayunmathur.findfamily.tracker.poweredOffGrantSigningBytes
import com.vayunmathur.findfamily.tracker.TrackerBeaconScanner
import com.vayunmathur.findfamily.tracker.TrackerReporting
import com.vayunmathur.findfamily.tracker.TrackerStore
import com.vayunmathur.findfamily.MainActivity
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.service.SharingTileService
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.work.startRepeatedTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
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

    /** The pre-unlock publish loop; null once the user unlocks and the normal path takes over. */
    private var directBootJob: Job? = null

    /**
     * Guards against double-registering listeners when the pre-unlock path has already
     * registered them and [startTracking] then runs after unlock.
     */
    private var sensorsRegistered = false

    /** Signature of the roster last written to [DirectBootStore], to avoid rewriting it every tick. */
    private var lastSeededMirror: String? = null

    /**
     * Who a publish would go to right now, sharing switches already applied.
     *
     * Kept in memory purely so [FinalLocationReporter] can read it without a disk round-trip on
     * the shutdown path, where the whole budget is a couple of seconds.
     */
    @Volatile
    private var publishRoster: List<DirectBootStore.Target> = emptyList()

    /** Serializes the off-reader enrichment batches (see [processIncomingLocations]). */
    private val enrichmentMutex = Mutex()

    // Custom UWB tracker feature (DEV_BUILD only). Owner-side store of tracker
    // secrets/private keys, and the finder-side beacon scan job.
    private var trackerStore: TrackerStore? = null
    private var trackerScanJob: Job? = null

    // Powered-off finding. Not DEV_BUILD gated: the finder half is ordinary BLE and is meant to
    // work on any phone with findfamily installed.
    private var poweredOffKeys: PoweredOffKeyStore? = null
    private var poweredOffScanJob: Job? = null
    private var lastPoweredOffPollMs = 0L

    private val networkListener = LocationListener { location ->
        recordFix(location)
        if (location.accuracy > 100f) {
            if (!isGpsRunning && isMoving) startGps()
        } else {
            if (isGpsRunning) stopGps()
        }
    }
    private val gpsListener = LocationListener { location ->
        recordFix(location)
    }

    /**
     * Keeps the best recent fix rather than simply the newest one.
     *
     * The network provider delivers a fix every ten seconds and its answer wanders by tens
     * to hundreds of metres between them, so overwriting a recent GPS fix with one of those
     * made a phone sitting on a table appear to move around the city. A coarser fix only
     * wins once the one being held has gone stale enough to be the worse answer.
     */
    private fun recordFix(location: Location) {
        val held = lastKnownLocation
        if (held == null) {
            lastKnownLocation = location
            return
        }
        val age = location.elapsedRealtimeNanos - held.elapsedRealtimeNanos
        if (age <= 0) return
        if (age > FIX_MAX_AGE_NANOS || location.accuracy <= held.accuracy) {
            lastKnownLocation = location
        }
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

            // The sharing tile (GitHub #648) suppresses outbound publishing without touching the
            // per-person switches, so turning it back on resumes exactly the set of people the
            // user was sharing with. Receiving, waypoints and tracker reporting are unaffected.
            val sharingOut = LocationServiceController.isGlobalSharingEnabled(this)
            val publishTargets = if (!sharingOut) emptyList<User>()
            else publishBaseUsers.filter { it.id != Networking.userid && it.sendingEnabled }
            Log.d("FF-Heartbeat", "publish targets count=${publishTargets.size} ids=${publishTargets.map{ it.id.toULong() }} names=${publishTargets.map{ it.name }} globalSharing=$sharingOut")
            publishTargets.forEach {
                val result = runCatching { Networking.publishLocation(locationValue, it) }
                if (result.isFailure) Log.w("FF-Heartbeat", "publish to ${it.id.toULong()} threw", result.exceptionOrNull())
            }
            if (sharingOut) currentLinks.filter { now < it.deleteAt }.forEach {
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
     * Mirror the identity, switches and sharing roster into device-protected storage so the
     * next reboot can report before the passcode is entered.
     *
     * Reads its own state rather than borrowing the heartbeat's, because the heartbeat gives
     * up early when there is no fix yet — and a device that has never had a fix still needs a
     * seeded mirror. Rewritten only when the result would differ, since this runs every tick.
     */
    private suspend fun seedDirectBootMirror() {
        val sharingOut = LocationServiceController.isGlobalSharingEnabled(this)
        val trackingEnabled = LocationServiceController.isTrackingEnabled(this)
        val targets = repository.getAllUsers()
            .filter { it.id != Networking.userid && it.sendingEnabled }
            .mapNotNull { u -> u.pqcEncryptionKey?.let { DirectBootStore.Target(u.id, it) } }
        val signature = "$sharingOut|$trackingEnabled|" + targets.joinToString(",") { "${it.id}:${it.bundle.length}" }
        publishRoster = if (sharingOut) targets else emptyList()
        if (signature == lastSeededMirror) return
        DirectBootStore.seed(
            this,
            targets,
            trackingEnabled = trackingEnabled,
            globalSharingEnabled = sharingOut,
        )
        lastSeededMirror = signature
        Log.i(TAG_DIRECT_BOOT, "mirror seeded: ${targets.size} target(s) sharing=$sharingOut tracking=$trackingEnabled")
    }

    /**
     * Persists a batch of freshly-decrypted peer locations and inserts unknown senders.
     * Runs on the live WebSocket reader coroutine, so it only does fast, durable work;
     * the slow best-effort part is handed to [enrichIncomingLocations]. Self-contained
     * (re-reads users) so it can be driven by any inbound path.
     */
    private suspend fun processIncomingLocations(incoming: List<LocationValue>) {
        // A category this build cannot interpret is dropped rather than stored. Marking it is not
        // enough: getLatest() ranks on reportedAt, so it would still become the newest row for
        // that person and be drawn as their position — and at least one such category
        // (NETWORK_SIGHTING) carries someone ELSE's coordinate.
        //
        // The log names who was dropped, not just how many. This is the one failure mode that is
        // otherwise invisible: if a future build ever emits a new source as someone's primary
        // stream, that person silently disappears from this map, and "Alice was dropped" is the
        // only thread anyone will have to pull on. See the note on LocationSource before adding
        // a value that could cause it.
        val locList = incoming.filter { it.source != LocationSource.UNKNOWN }
        if (locList.size != incoming.size) {
            val dropped = incoming.filter { it.source == LocationSource.UNKNOWN }.map { it.userid.toULong() }.distinct()
            Log.w("FF-Heartbeat", "dropped ${incoming.size - locList.size} fix(es) from newer peer(s) with an unrecognised source: $dropped")
        }
        if (locList.isEmpty()) return
        val currentUsers = repository.getAllUsers()
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

        // Enrichment runs off the reader coroutine. Reverse-geocoding is a slow network
        // call, so doing it inline stalled every subsequent inbound frame until the
        // liveness timeout force-reconnected the socket. The mutex keeps batches
        // serialized, so entry/exit and low-battery alerts still fire once each.
        serviceScope.launch {
            enrichmentMutex.withLock { enrichIncomingLocations(locList, latestMap) }
        }
    }

    /**
     * Best-effort follow-up to [processIncomingLocations]: recomputes waypoint
     * entry/exit, reverse-geocodes the display name, and raises the entry/exit and
     * low-battery notifications. [latestMap] is the latest-per-user snapshot taken
     * *before* the new fixes were stored, so the comparisons below see the prior state.
     */
    private suspend fun enrichIncomingLocations(
        locList: List<LocationValue>,
        latestMap: Map<Long, LocationValue>,
    ) {
        val currentUsers = repository.getAllUsers()
        val currentWaypoints = repository.getAllWaypoints()

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

            val accuracy = lastLoc.acc.toDouble()
            val prevId = user.lastWaypointId
            // A fix that cannot say which side of the boundary it is on must not move the
            // answer. A stationary phone on network fixes wanders far enough to cross and
            // re-cross a 100 m geofence every few seconds, and every crossing notified.
            val currentId: Long? = if (accuracy > GEOFENCE_MAX_ACCURACY_METERS) {
                prevId
            } else {
                // Entering needs the whole error circle inside; leaving needs it wholly
                // outside the hysteresis margin, so the edge cases stay where they were.
                val entered = currentWaypoints.find {
                    havershine(it.coord, lastLoc.coord) + accuracy < it.range
                }
                val stillInsidePrev = prevId?.let { pid ->
                    currentWaypoints.find { it.id == pid }?.let {
                        havershine(it.coord, lastLoc.coord) - accuracy < it.range * WAYPOINT_EXIT_HYSTERESIS
                    }
                } ?: false
                entered?.id ?: prevId.takeIf { stillInsidePrev }
            }
            val currentWaypoint = currentWaypoints.find { it.id == currentId }

            // Display name: prefer the waypoint we are in, then the geocoded address.
            val displayName = currentWaypoint?.name
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
                    val enteredName = currentWaypoint?.name ?: displayName
                    notifyEntryExit(user, getString(R.string.notification_entered_waypoint, user.name, enteredName), arrival = true)
                } else if (prevId != null) {
                    val exitedName = currentWaypoints.find { it.id == prevId }?.name ?: user.locationName
                    notifyEntryExit(user, getString(R.string.notification_exited_waypoint, user.name, exitedName), arrival = false)
                }
            }
        }
    }

    /**
     * Forwards decrypted UWB envelopes to [UwbInbox] and fires a local
     * notification for REQUEST envelopes. Driven by the live WebSocket push.
     *
     * Powered-off recovery grants ride the same channel (see [UwbEnvelopeKind.POF_GRANT]) and are
     * handled here instead, deliberately without reaching [UwbInbox]: they are not ranging
     * traffic and have no business waking the Find Nearby screen.
     */
    private suspend fun handleUwbEnvelopes(list: List<UwbEnvelope>) {
        if (list.isEmpty()) return
        val users = repository.getAllUsers()
        for (envelope in list) {
            when (envelope.kind) {
                UwbEnvelopeKind.POF_GRANT -> acceptPoweredOffGrant(envelope)
                else -> {
                    UwbInbox.tryEmit(envelope)
                    if (envelope.kind == UwbEnvelopeKind.REQUEST) {
                        val senderId = envelope.sender.toLong()
                        val senderName = users.firstOrNull { it.id == senderId }?.name
                            ?: getString(R.string.uwb_unknown_peer_name)
                        createUwbRequestNotification(senderName, senderId)
                    }
                }
            }
        }
    }

    /**
     * Store a family member's powered-off keys so this device can go and find their lost phone.
     *
     * Everything after this is already built: [PoweredOffKeyStore] is keyed by userid and
     * [pollPoweredOffSightings] already walks every user it can read, so filing the keys here is
     * the entire receiving side.
     *
     * Three reasons to refuse, all of them silent by design — a rejected grant is either an
     * attack or a stale duplicate, and neither is worth a notification:
     *  - the sender is not someone we have a [User] row for, so nobody chose to trust them;
     *  - the signature does not verify against that sender's identity bundle. The envelope is
     *    encrypted to us but its `sender` field is self-declared, so without this check any
     *    connected peer could deliver keys under a different family member's userid and have
     *    every sighting decrypted from them drawn on the map as that person's phone;
     *  - the grant is older than one we already hold, which happens when a revoke-triggered
     *    redistribution overtakes the grant it replaces.
     */
    private suspend fun acceptPoweredOffGrant(envelope: UwbEnvelope) {
        val store = poweredOffKeys ?: return
        val grant = envelope.recovery ?: return
        val ownerId = envelope.sender.toLong()
        if (ownerId == 0L || ownerId == Networking.userid) return
        if (repository.getUser(ownerId) == null) {
            Log.w(TAG_POWERED_OFF, "recovery grant from unknown sender, ignored")
            return
        }
        val decoded = runCatching {
            Triple(
                Base64.decode(grant.secretB64),
                Base64.decode(grant.recoveryPrivB64),
                Base64.decode(grant.sigB64),
            )
        }.getOrNull() ?: return
        val (secret, recoveryPriv, signature) = decoded

        val signed = poweredOffGrantSigningBytes(
            owner = ownerId,
            recipient = Networking.userid,
            epoch = grant.epoch,
            secret = secret,
            recoveryPrivate = recoveryPriv,
        )
        if (!Networking.verifyFrom(ownerId, signed, signature)) {
            Log.w(TAG_POWERED_OFF, "recovery grant signature did not verify, ignored")
            return
        }
        if (grant.epoch < store.epoch(ownerId)) {
            Log.i(TAG_POWERED_OFF, "ignoring superseded recovery grant (epoch ${grant.epoch})")
            return
        }
        store.save(ownerId, secret, recoveryPriv, grant.epoch)
        Log.i(TAG_POWERED_OFF, "stored recovery keys for a peer (epoch ${grant.epoch})")
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

    /**
     * Finder path for powered-off devices. Unlike the tracker scanner above this is **not**
     * DEV_BUILD gated and needs no privileged permission — the whole point is that any phone
     * with findfamily on it can contribute sightings. It is gated on the user having opted in.
     *
     * Collecting [LocationServiceController.crowdFindingEnabledFlow] rather than reading the
     * flag once means flipping the switch off actually stops the radio, instead of leaving it
     * scanning until the service happens to restart.
     */
    private fun startPoweredOffScanner() {
        if (poweredOffScanJob?.isActive == true) return
        poweredOffScanJob = serviceScope.launch {
            LocationServiceController.crowdFindingEnabledFlow(this@LocationTrackingService)
                .collectLatest { enabled ->
                    if (!enabled) return@collectLatest
                    runCatching {
                        PoweredOffScanner(this@LocationTrackingService).sightings().collect { sighting ->
                            val loc = lastKnownLocation
                            if (loc == null) {
                                Log.i(TAG_POWERED_OFF, "sighting dropped: no location fix yet")
                                return@collect
                            }
                            // A sighting is only ever "the finder was near here". Reporting one
                            // from a 500m-accurate fix would add noise the owner cannot tell
                            // apart from a good one, so drop it rather than dilute the answer.
                            if (loc.accuracy > 100f) {
                                Log.i(TAG_POWERED_OFF, "sighting dropped: accuracy ${loc.accuracy}m > 100m")
                                return@collect
                            }
                            val lv = LocationValue(
                                Networking.userid,
                                Coord(loc.latitude, loc.longitude),
                                0f,
                                loc.accuracy,
                                Clock.System.now(),
                                // The finder's own battery is none of the owner's business, and
                                // sending it would leak a little about who did the finding.
                                0f,
                            )
                            runCatching { PoweredOffReporting.reportSighting(sighting, lv) }
                                .onFailure { Log.w(TAG_POWERED_OFF, "reportSighting failed", it) }
                        }
                    }.onFailure { Log.w(TAG_POWERED_OFF, "powered-off scan collect failed", it) }
                }
        }
    }

    /**
     * Owner path for powered-off devices: for every peer whose powered-off keys we hold, drain
     * and decrypt any sightings and feed them through the normal incoming pipeline. Finding
     * nothing is the ordinary case and is not worth logging at anything above debug.
     *
     * Rate-limited to [POWERED_OFF_POLL_INTERVAL_MS] rather than running on the 30s heartbeat.
     * A query carries one handle per armed slot — 258 of them, about 4KB — and an EID only
     * rotates every 1024s, so polling every 30s would send that 34 times before there could
     * possibly be a new handle to ask about.
     */
    private suspend fun pollPoweredOffSightings() {
        val store = poweredOffKeys ?: return
        val now = System.currentTimeMillis()
        if (now - lastPoweredOffPollMs < POWERED_OFF_POLL_INTERVAL_MS) return
        lastPoweredOffPollMs = now
        val users = runCatching { repository.getAllUsers() }.getOrDefault(emptyList())
        val locs = ArrayList<LocationValue>()
        for (u in users) {
            if (!store.canRead(u.id)) continue
            locs += runCatching { PoweredOffReporting.fetchSightings(u.id, store) }
                .getOrDefault(emptyList())
        }
        if (locs.isNotEmpty()) {
            Log.i(TAG_POWERED_OFF, "retrieved ${locs.size} network sighting(s)")
            processIncomingLocations(locs)
        }
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
        // Runtime registration is not a style choice: neither ACTION_SHUTDOWN nor
        // ACTION_BATTERY_LOW reaches a manifest-declared receiver.
        FinalLocationReporter.start(this, { lastKnownLocation }, { publishRoster })
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

        if (isUserUnlocked()) startTracking() else startDirectBootTracking()
        return START_STICKY
    }

    /**
     * Before the first unlock after a reboot, Room and the default DataStore are
     * credential-encrypted and unreadable, so the normal path cannot run at all. Publish from
     * the device-protected mirror instead and hand over the moment the user unlocks.
     */
    private fun isUserUnlocked(): Boolean =
        getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_UNLOCKED) onUserUnlocked()
        }
    }
    private var unlockReceiverRegistered = false

    private fun startDirectBootTracking() {
        if (directBootJob?.isActive == true) return
        if (!unlockReceiverRegistered) {
            registerReceiver(
                unlockReceiver,
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
                RECEIVER_NOT_EXPORTED,
            )
            unlockReceiverRegistered = true
        }
        directBootJob = serviceScope.launch {
            val ctx = this@LocationTrackingService
            // Expected on the first boot after this ships, and after a factory reset: there is
            // nothing to publish with yet. Seeding happens below once the user unlocks.
            if (!DirectBootStore.isSeeded(ctx)) {
                Log.i(TAG_DIRECT_BOOT, "no device-protected mirror yet; idle until first unlock")
                return@launch
            }
            if (!DirectBootStore.isTrackingEnabled(ctx)) {
                Log.i(TAG_DIRECT_BOOT, "tracking switched off by the user; staying idle")
                return@launch
            }
            if (!Networking.initDirectBoot(DirectBootStore.store(ctx))) {
                Log.w(TAG_DIRECT_BOOT, "identity unavailable from the mirror; staying idle")
                return@launch
            }

            withContext(Dispatchers.Main) {
                registerSensors()
                isMoving = true
                lastMovementTime = System.currentTimeMillis()
                setupLocationUpdates()
            }
            // Inbound delivery needs Room to persist anything, so the pre-unlock socket is
            // publish-only. Peers' locations are picked up on reconnect after unlock.
            Networking.startLive(serviceScope, onLocations = {}, onUwb = {})

            val sharing = DirectBootStore.isGlobalSharingEnabled(ctx)
            val targets = if (sharing) DirectBootStore.roster(ctx) else emptyList()
            publishRoster = targets
            Log.i(TAG_DIRECT_BOOT, "running pre-unlock, sharing=$sharing targets=${targets.size}")
            while (isActive) {
                publishDirectBoot(targets)
                delay(30.seconds)
            }
        }
    }

    /** The pre-unlock equivalent of [syncHeartbeat]: publish only, no database, no enrichment. */
    private suspend fun publishDirectBoot(targets: List<DirectBootStore.Target>) {
        val location = lastKnownLocation ?: run {
            Log.d(TAG_DIRECT_BOOT, "no fix yet")
            return
        }
        if (targets.isEmpty()) return
        val battery = runCatching {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
        }.getOrDefault(0f)
        val lv = LocationValue(
            Networking.userid,
            Coord(location.latitude, location.longitude),
            0f,
            location.accuracy,
            Clock.System.now(),
            battery,
        )
        Log.d(TAG_DIRECT_BOOT, "publishing ${location.latitude},${location.longitude} acc=${location.accuracy} to ${targets.size} peer(s)")
        targets.forEach {
            try {
                Networking.publishLocation(lv, it.id, it.bundle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG_DIRECT_BOOT, "publish to ${it.id.toULong()} failed", e)
            }
        }
    }

    private fun onUserUnlocked() {
        serviceScope.launch {
            Log.i(TAG_DIRECT_BOOT, "user unlocked; handing over to the normal path")
            directBootJob?.cancelAndJoin()
            directBootJob = null
            try {
                Networking.promoteToUnlocked(
                    repository,
                    DataStoreUtils.getInstance(this@LocationTrackingService),
                    getString(R.string.me_label),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG_DIRECT_BOOT, "handover failed", e)
            }
            startTracking()
        }
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
                // Device-protected, and it MUST match PoweredOffBeacon.keys(). The beacon half
                // arms from a shutdown broadcast that can fire before first unlock — a flat
                // battery does not wait for a passcode — so it writes the beacon secret and the
                // ML-KEM private bundle to the direct-boot store. Reading them back from the
                // ordinary credential-protected store would find nothing, canRead() would be
                // false for every user forever, and retrieval would return zero sightings with
                // no error anywhere. Keep these two constructions pointing at the same store.
                poweredOffKeys = PoweredOffKeyStore(DirectBootStore.store(this@LocationTrackingService))

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
                    try {
                        seedDirectBootMirror()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG_DIRECT_BOOT, "mirror seed failed", e)
                    }
                    if (BuildConfig.DEV_BUILD) runCatching { pollTrackerReports() }
                    runCatching { pollPoweredOffSightings() }
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

            // Powered-off finding, finder half. Opt-in, and available on any build.
            startPoweredOffScanner()
        }
    }

    private fun registerSensors() {
        if (sensorsRegistered) return
        sensorsRegistered = true
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
        private const val TAG_DIRECT_BOOT = "FF-DirectBoot"
        private const val TAG_POWERED_OFF = "FF-PoweredOff"

        /** How often to drain powered-off sightings. See [pollPoweredOffSightings]. */
        private const val POWERED_OFF_POLL_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * How stale the held fix has to be before a less accurate one replaces it.
         *
         * Long enough that a burst of coarse network fixes cannot displace a good GPS one,
         * short enough that a device which has lost GPS still reports where it now is.
         */
        private val FIX_MAX_AGE_NANOS = 2.minutes.inWholeNanoseconds

        /**
         * Accuracy beyond which a fix is not used to decide geofence membership. Matches the
         * gate the tracker sighting path already applies.
         */
        private const val GEOFENCE_MAX_ACCURACY_METERS = 100.0

        /** How far past a geofence's radius a fix has to be before it counts as having left. */
        private const val WAYPOINT_EXIT_HYSTERESIS = 1.2
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
     * Post an arrival/departure notification on the person's own per-event channel (issue #618),
     * so its sound, vibration and DND behaviour can be tuned independently in system settings.
     */
    private fun notifyEntryExit(user: User, message: String, arrival: Boolean) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        FindFamilyNotificationChannels.ensureEntryExitChannels(this, user.id, user.name)
        val channelId = FindFamilyNotificationChannels.entryExitChannelId(user.id, arrival)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(user.name)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()
        val event = if (arrival) "ARRIVAL" else "DEPARTURE"
        val notificationId = "${user.id}::ENTRY_EXIT::$event".hashCode()
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
        FinalLocationReporter.stop(this)
        if (unlockReceiverRegistered) {
            runCatching { unregisterReceiver(unlockReceiver) }
            unlockReceiverRegistered = false
        }
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

/**
 * Reverse-geocodes a coordinate, or null if it can't be resolved.
 *
 * Devices without Play Services / microG (e.g. GrapheneOS) have no geocode backend at
 * all, so [Geocoder.isPresent] is checked first. The async API also reports failures via
 * `onError`, which must be implemented: a bare lambda only supplies `onGeocode`, leaving
 * the continuation unresumed forever on any error. The timeout is the final backstop —
 * this is a network call, and hanging here blocks whoever is awaiting it.
 */
suspend fun Context.fetchAddress(lat: Double, lng: Double): Address? {
    if (!Geocoder.isPresent()) return null
    return withTimeoutOrNull(15.seconds) {
        suspendCancellableCoroutine { continuation ->
            val geocoder = Geocoder(this@fetchAddress, Locale.getDefault())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Modern Async API (Android 13+)
                geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
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
        }
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

    /**
     * Persisted on/off switch for publishing this device's location to anyone, toggled
     * from the Quick Settings sharing tile (default on — see SharingTileService).
     *
     * Deliberately separate from the per-person `sendingEnabled` flags so pausing and
     * resuming restores whoever was being shared with, and from [TRACKING_ENABLED_KEY]
     * so the service keeps running: peers' locations, waypoint entry/exit and UWB
     * tracker reporting all continue while sharing is paused.
     */
    const val GLOBAL_SHARING_ENABLED_KEY = "global_sharing_enabled"

    /**
     * Whether this phone acts as a finder for other people's powered-off devices.
     *
     * MANDATORY. There is no toggle: the finder half is part of what the app is, and the
     * Bluetooth permission behind it is now requested on the initial permission screen alongside
     * location. The key is retained only so an existing install's stored value is not orphaned;
     * nothing reads it any more.
     */
    const val CROWD_FINDING_ENABLED_KEY = "crowd_finding_enabled"

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

    /** Whether outbound sharing is on. Defaults to true (opt-out, not opt-in). */
    suspend fun isGlobalSharingEnabled(context: Context): Boolean =
        DataStoreUtils.getInstance(context).getBooleanAwait(GLOBAL_SHARING_ENABLED_KEY, true)

    /** [isGlobalSharingEnabled] as a stream, for the UI to grey out the per-person switches. */
    fun globalSharingEnabledFlow(context: Context): Flow<Boolean> =
        DataStoreUtils.getInstance(context).booleanFlow(GLOBAL_SHARING_ENABLED_KEY, true)

    /**
     * Persist the outbound-sharing choice. The next heartbeat picks it up; the service
     * itself is left alone, so this never stops receiving or tracker reporting.
     */
    suspend fun setGlobalSharingEnabled(context: Context, enabled: Boolean) {
        DataStoreUtils.getInstance(context).setBoolean(GLOBAL_SHARING_ENABLED_KEY, enabled)
        SharingTileService.requestRefresh(context)
    }

    /** Whether the user has agreed to act as a finder. Defaults to **false** — opt-in. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun isCrowdFindingEnabled(context: Context): Boolean = true

    /**
     * [isCrowdFindingEnabled] as a stream. Constant now that finding is mandatory - kept as a
     * Flow so the collector in the service is unchanged, and so a future re-introduction of a
     * user control does not have to re-plumb the call site.
     */
    @Suppress("UNUSED_PARAMETER")
    fun crowdFindingEnabledFlow(context: Context): Flow<Boolean> = flowOf(true)

    suspend fun setCrowdFindingEnabled(context: Context, enabled: Boolean) {
        DataStoreUtils.getInstance(context).setBoolean(CROWD_FINDING_ENABLED_KEY, enabled)
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

    /**
     * Boot-time start for the window before the first unlock.
     *
     * Deliberately does not consult [isTrackingEnabled] — that reads the credential-encrypted
     * DataStore, which is unreadable until the passcode is entered. Eligibility comes from the
     * device-protected mirror instead, and an unseeded mirror means we stay off rather than
     * guess. Never stops the service, since "not eligible yet" here only means "cannot tell".
     */
    suspend fun syncServiceStateLocked(context: Context) {
        val appContext = context.applicationContext
        if (!hasFineLocationPermission(appContext)) return
        if (!DirectBootStore.isSeeded(appContext)) return
        if (!DirectBootStore.isTrackingEnabled(appContext)) return
        withContext(Dispatchers.Main) {
            runCatching {
                appContext.startForegroundService(
                    Intent(appContext, LocationTrackingService::class.java)
                )
            }
        }
    }
}

fun ensureSync(context: Context) {
    startRepeatedTask<ServiceRestartWorker>(
        context, "Location Sync", 15.minutes,
        ExistingWorkPolicy.REPLACE, ExistingPeriodicWorkPolicy.REPLACE
    )
}