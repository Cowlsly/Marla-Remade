package com.vayunmathur.maps.util
import android.app.Application
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiDataSource
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.google.WebReviewsFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.maplibre.spatialk.geojson.Position

class SelectedFeatureViewModel(application: Application): AndroidViewModel(application) {
    private val _selectedFeature = MutableStateFlow<SpecificFeature?>(null)
    val selectedFeature = _selectedFeature.asStateFlow()

    private val _inactiveNavigation = MutableStateFlow<SpecificFeature.Route?>(null)
    val inactiveNavigation = _inactiveNavigation.asStateFlow()

    /** A pending request for the map to fly to [position] (at [zoom] when set) and
     *  show the place bottom PANE (peek), the Vela-style place card. Backed by a
     *  StateFlow so a request made before MapPage is composed (a cold-start deep
     *  link) survives until the map consumes it via [consumeFocus]. */
    data class PlaceFocus(val position: Position, val zoom: Double? = null)

    private val _pendingFocus = MutableStateFlow<PlaceFocus?>(null)
    val pendingFocus = _pendingFocus.asStateFlow()

    private val _userPosition = MutableStateFlow(Position(0.0, 0.0))
    val userPosition = _userPosition.asStateFlow()

    private val _userBearing = MutableStateFlow(0f)
    val userBearing = _userBearing.asStateFlow()

    // Magnetometer accuracy backing the compass calibration banner. Defaults to
    // HIGH so the banner stays hidden until the sensor reports a lower value.
    private val _userHeadingAccuracy = MutableStateFlow(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
    val userHeadingAccuracy = _userHeadingAccuracy.asStateFlow()

    val locationManager = FrameworkLocationManager(application)

    // Hidden-WebView reviews scraper (keyless; Google 404'd the old reviews RPC). Holds a single
    // WebView built from the application Context, serialized + idle-reaped internally.
    private val webReviews = WebReviewsFetcher(application)

    init {
        locationManager.startUpdates(
            onUpdateReceived = { position, bearing ->
                _userPosition.value = position
                _userBearing.value = bearing
            },
            onAccuracyReceived = { accuracy ->
                _userHeadingAccuracy.value = accuracy
            },
        )
    }

    override fun onCleared() {
        // Unregister GPS + sensor listeners so the radio doesn't keep draining
        // battery after the user navigates away from the map module.
        locationManager.stop()
    }

    fun set(feature: SpecificFeature?) {
        _selectedFeature.value = feature
    }

    /**
     * Select [feature] AND ask the map to fly to it and open the place bottom pane
     * (peek). This is the single "search → auto-select first / deep link → open a
     * place" path shared by the contact-address shortcut (P17) and the external
     * intent handler (geo:/maps links). Selecting a [SpecificFeature.RoutableFeature]
     * triggers [currentPoiInfo] enrichment so the pane fills with details.
     */
    fun selectAndFocus(feature: SpecificFeature, zoom: Double? = null) {
        _selectedFeature.value = feature
        val pos = (feature as? SpecificFeature.RoutableFeature)?.position
        _pendingFocus.value = pos?.let { PlaceFocus(it, zoom) }
    }

    /** Clear a consumed [pendingFocus] once the map has flown there + opened the pane. */
    fun consumeFocus() {
        _pendingFocus.value = null
    }

    fun setInactiveNavigation(route: SpecificFeature.Route?) {
        _inactiveNavigation.value = route
    }

    /**
     * Keyless Google Maps enrichment (rating, reviews, hours, photos, price,
     * popular times, …) for the currently selected restaurant or generic place.
     * Emits null for other feature types and while the network scrape is in
     * flight; cancels the in-flight fetch when the selection changes.
     * [GooglePoiDataSource] caches and does its own IO, so a re-select is instant.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentPoiInfo: StateFlow<GooglePoiInfo?> = selectedFeature
        .flatMapLatest { feature ->
            val (name, pos) = when (feature) {
                is SpecificFeature.Restaurant -> feature.name to feature.position
                is SpecificFeature.GenericPlace -> feature.name to feature.position
                else -> return@flatMapLatest flowOf(null)
            }
            if (name.isBlank()) return@flatMapLatest flowOf(null)
            // channelFlow so the WebView scrape's onPartial callback (arriving on a JavaBridge
            // thread) can push progressive review updates into the same stream via trySend.
            channelFlow {
                send(null)
                val base = GooglePoiDataSource.fetch(name, pos.latitude, pos.longitude)
                send(base)
                // Base sheet is up. Reviews load lazily via the hidden WebView: no feature id
                // (e.g. no confident Google match) → nothing more to fetch. Best-effort; any
                // failure/timeout just leaves reviews empty and the sheet as-is.
                val fid = base?.featureId
                if (base != null && !fid.isNullOrBlank()) {
                    val reviews = runCatching {
                        webReviews.fetch(
                            featureId = fid,
                            onPartial = { list ->
                                if (list.isNotEmpty()) trySend(base.copy(reviews = list))
                            },
                        )
                    }.getOrDefault(emptyList())
                    if (reviews.isNotEmpty()) send(base.copy(reviews = reviews))
                }
            }.flowOn(Dispatchers.IO)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // Move heavy computation to a background StateFlow
    @OptIn(ExperimentalCoroutinesApi::class)
    val routes = selectedFeature
        .flatMapLatest { feature ->
            val pos = userPosition.value
            val routeFeature = feature as? SpecificFeature.Route ?: return@flatMapLatest flowOf(null)

            // Create a flow that emits results one by one
            flow {
                // Start with an empty map
                emit(emptyMap())

                // Offline-first routing with chained multi-waypoint support.
                // TRANSIT prefers the on-device RAPTOR planner over any
                // downloaded region index (P11d); if none covers the trip, it
                // falls back to the P10 online Transitous (MOTIS) planner.
                // getRouteForMode owns that split so the road graph, which has no
                // timetable, never sees TRANSIT.
                RouteService.TravelMode.entries.forEach { mode ->
                    val result = try {
                        OfflineRouter.getRouteForMode(application, routeFeature, pos, mode)
                    } catch (_: Exception) {
                        null
                    }
                    emit(mapOf(mode to (result ?: RouteService.EmptyRoute())))
                }
            }
                .scan(RouteService.TravelMode.entries.associateWith { null as RouteService.RouteType? }) { accumulator, newEntry ->
                    accumulator + newEntry // Combine the old map with the new calculation
                }
                .flowOn(Dispatchers.Default)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
}
