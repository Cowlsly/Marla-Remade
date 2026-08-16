package com.vayunmathur.maps.util
import android.app.Application
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiDataSource
import com.vayunmathur.maps.data.google.GooglePoiInfo
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
            flow {
                emit(null)
                emit(GooglePoiDataSource.fetch(name, pos.latitude, pos.longitude))
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
