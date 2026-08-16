package com.vayunmathur.maps.util
import android.app.Application
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.maplibre.spatialk.geojson.Position

class SelectedFeatureViewModel(application: Application): AndroidViewModel(application) {
    private val _selectedFeature = MutableStateFlow<SpecificFeature?>(null)
    val selectedFeature = _selectedFeature.asStateFlow()

    private val _inactiveNavigation = MutableStateFlow<SpecificFeature.Route?>(null)
    val inactiveNavigation = _inactiveNavigation.asStateFlow()

    private val _userPosition = MutableStateFlow(Position(0.0, 0.0))
    val userPosition = _userPosition.asStateFlow()

    private val _userBearing = MutableStateFlow(0f)
    val userBearing = _userBearing.asStateFlow()

    // Magnetometer accuracy backing the compass calibration banner. Defaults to
    // HIGH so the banner stays hidden until the sensor reports a lower value.
    private val _userHeadingAccuracy = MutableStateFlow(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
    val userHeadingAccuracy = _userHeadingAccuracy.asStateFlow()

    val locationManager = FrameworkLocationManager(application)

    // Small cache of reviews keyed by (name, lat, lon) to avoid refetching the
    // same place when the user toggles back and forth. Bounded to cap memory.
    private data class ReviewKey(val name: String, val lat: Double, val lon: Double)
    private val reviewsCache = object : LinkedHashMap<ReviewKey, FullPlaceInfo?>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ReviewKey, FullPlaceInfo?>
        ): Boolean = size > 32
    }

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

    fun setInactiveNavigation(route: SpecificFeature.Route?) {
        _inactiveNavigation.value = route
    }

    /**
     * Reviews for the currently selected restaurant or generic place. Emits null
     * for other feature types or while the network call is in flight. Cancels
     * the in-flight fetch when the selection changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentReviews: StateFlow<FullPlaceInfo?> = selectedFeature
        .flatMapLatest { feature ->
            val (name, pos) = when (feature) {
                is SpecificFeature.Restaurant -> feature.name to feature.position
                is SpecificFeature.GenericPlace -> feature.name to feature.position
                else -> return@flatMapLatest flowOf(null)
            }
            val key = ReviewKey(name, pos.latitude, pos.longitude)
            val cached: FullPlaceInfo? = synchronized(reviewsCache) { reviewsCache[key] }
            if (cached != null) return@flatMapLatest flowOf(cached)
            flow {
                emit(null)
                val info = Reviews.getRatingForOsmLocation(name, pos.latitude, pos.longitude)
                synchronized(reviewsCache) { reviewsCache[key] = info }
                emit(info)
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

                // Offline-only routing with chained multi-waypoint support
                RouteService.TravelMode.entries.forEach { mode ->
                    val result = try {
                        OfflineRouter.getRouteMulti(application, routeFeature, pos, mode)
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
