package com.vayunmathur.weather.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.glance.appwidget.updateAll
import com.vayunmathur.weather.R
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.data.WeatherRepository
import com.vayunmathur.weather.data.WeatherRefreshWorker
import com.vayunmathur.weather.data.weatherJson
import com.vayunmathur.weather.glance.WeatherGlanceWidget
import com.vayunmathur.weather.network.AirQualityResponse
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.WeatherApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ForecastUiState(
    val forecast: ForecastResponse? = null,
    val airQuality: AirQualityResponse? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val fetchedAtEpochMs: Long = 0,
)

sealed interface SelectedDateOrTime {
    data class Time(val isoTime: String) : SelectedDateOrTime
    data class Day(val isoDate: String) : SelectedDateOrTime
}

class WeatherViewModel(
    application: Application,
    private val repository: WeatherRepository,
) : AndroidViewModel(application), WeatherActions {

    val savedLocations: StateFlow<List<SavedLocation>?> = repository.locations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _forecasts = MutableStateFlow<Map<Long, ForecastUiState>>(emptyMap())
    val forecasts: StateFlow<Map<Long, ForecastUiState>> = _forecasts.asStateFlow()

    private val inFlight = mutableSetOf<Long>()

    private val _selected = MutableStateFlow<SelectedDateOrTime?>(null)
    val selectedDateOrTime: StateFlow<SelectedDateOrTime?> = _selected.asStateFlow()

    fun selectTime(isoTime: String) { _selected.value = SelectedDateOrTime.Time(isoTime) }

    fun selectDay(isoDate: String) { _selected.value = SelectedDateOrTime.Day(isoDate) }

    override fun clearSelection() { _selected.value = null }

    override fun toggleTime(isoTime: String) {
        val current = _selected.value
        _selected.value = if (current is SelectedDateOrTime.Time && current.isoTime == isoTime) null else SelectedDateOrTime.Time(isoTime)
    }

    override fun toggleDay(isoDate: String) {
        val current = _selected.value
        _selected.value = if (current is SelectedDateOrTime.Day && current.isoDate == isoDate) null else SelectedDateOrTime.Day(isoDate)
    }

    init {
        WeatherRefreshWorker.scheduleHourlyRefresh(application)
    }

    fun ensureForecast(location: SavedLocation, force: Boolean = false) {
        synchronized(inFlight) {
            if (location.id in inFlight) return
            val existing = _forecasts.value[location.id]
            val memStale = existing?.forecast == null ||
                (System.currentTimeMillis() - existing.fetchedAtEpochMs) >= STALE_THRESHOLD_MS
            if (!force && !memStale) return
            inFlight.add(location.id)
        }

        viewModelScope.launch {
            try {
                var haveFreshCache = false
                if (_forecasts.value[location.id]?.forecast == null) {
                    val cache = repository.getCache(roundCoord(location.latitude), roundCoord(location.longitude))
                    if (cache != null) {
                        runCatching { weatherJson.decodeFromString<ForecastResponse>(cache.forecastJson) }
                            .onSuccess { decoded ->
                                val cachedAir = cache.airQualityJson?.let { json ->
                                    runCatching { weatherJson.decodeFromString<AirQualityResponse>(json) }.getOrNull()
                                }
                                _forecasts.update { current ->
                                    current + (location.id to ForecastUiState(
                                        forecast = decoded,
                                        airQuality = cachedAir,
                                        refreshing = false,
                                        fetchedAtEpochMs = cache.fetchedAtEpochMs,
                                    ))
                                }
                                haveFreshCache = (System.currentTimeMillis() - cache.fetchedAtEpochMs) < STALE_THRESHOLD_MS
                            }
                    }
                }

                if (!force && haveFreshCache) return@launch

                val target = if (location.isCurrent) refreshDeviceLocationFix(location) else location

                _forecasts.update { current ->
                    val prev = current[location.id]
                    current + (location.id to (prev?.copy(refreshing = true) ?: ForecastUiState(refreshing = true)))
                }

                data class FetchResult(val forecast: kotlin.Result<ForecastResponse>, val air: AirQualityResponse?)
                val fetched: FetchResult = coroutineScope {
                    val forecastDeferred = async { runCatching { WeatherApi.forecast(target.latitude, target.longitude) } }
                    val airQualityDeferred = async { runCatching { WeatherApi.airQuality(target.latitude, target.longitude) }.getOrNull() }
                    FetchResult(forecastDeferred.await(), airQualityDeferred.await())
                }
                val forecastResult = fetched.forecast
                val airQuality = fetched.air

                forecastResult
                    .onSuccess { fresh ->
                        val now = System.currentTimeMillis()
                        val resolvedAir = airQuality ?: _forecasts.value[location.id]?.airQuality
                        repository.writeForecastCache(target.latitude, target.longitude, fresh, resolvedAir, now)
                        _forecasts.update { current ->
                            current + (location.id to ForecastUiState(
                                forecast = fresh,
                                airQuality = resolvedAir,
                                refreshing = false,
                                error = null,
                                fetchedAtEpochMs = now,
                            ))
                        }
                        runCatching { WeatherGlanceWidget().updateAll(getApplication<Application>()) }
                    }
                    .onFailure { e ->
                        _forecasts.update { current ->
                            val prev = current[location.id]
                            current + (location.id to (prev?.copy(
                                airQuality = airQuality ?: prev.airQuality,
                                refreshing = false,
                                error = e.message ?: "Failed to load forecast",
                            ) ?: ForecastUiState(
                                airQuality = airQuality,
                                refreshing = false,
                                error = e.message,
                            )))
                        }
                    }
            } finally {
                synchronized(inFlight) { inFlight.remove(location.id) }
            }
        }
    }

    override fun refreshAll(force: Boolean) {
        for (location in savedLocations.value.orEmpty()) {
            ensureForecast(location, force)
        }
    }

    override fun deleteLocation(location: SavedLocation) {
        viewModelScope.launch {
            repository.deleteLocation(location)
            _forecasts.update { it - location.id }
        }
    }

    override fun reorderLocations(ordered: List<SavedLocation>) {
        viewModelScope.launch {
            ordered.forEachIndexed { index, loc ->
                if (loc.displayOrder != index) repository.setOrder(loc.id, index)
            }
        }
    }

    fun addLocation(name: String, country: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            val existing = repository.getLocations()
            repository.insertLocation(
                SavedLocation(
                    name = name,
                    country = country,
                    latitude = latitude,
                    longitude = longitude,
                    displayOrder = existing.size,
                    isCurrent = false,
                )
            )
        }
    }

    fun setCurrentLocation(name: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val defaultName = app.getString(R.string.current_location)
            repository.replaceCurrentDeviceLocation(
                SavedLocation(
                    name = name.ifBlank { defaultName },
                    country = "",
                    latitude = latitude,
                    longitude = longitude,
                    displayOrder = -1,
                    isCurrent = true,
                )
            )
        }
    }

    private suspend fun refreshDeviceLocationFix(location: SavedLocation): SavedLocation {
        val context = getApplication<Application>()
        if (!LocationProvider.hasPermission(context)) return location
        val fix = withTimeoutOrNull(LOCATION_FIX_TIMEOUT_MS) { LocationProvider.currentLocation(context) } ?: return location
        val distance = FloatArray(1)
        android.location.Location.distanceBetween(location.latitude, location.longitude, fix.latitude, fix.longitude, distance)
        if (distance[0] < MIN_LOCATION_MOVE_METERS) return location
        repository.updateCoordinates(location.id, fix.latitude, fix.longitude)
        return location.copy(latitude = fix.latitude, longitude = fix.longitude)
    }

    companion object {
        const val STALE_THRESHOLD_MS = 15 * 60 * 1000L
        private const val LOCATION_FIX_TIMEOUT_MS = 8_000L
        private const val MIN_LOCATION_MOVE_METERS = 500f
    }
}

class WeatherViewModelFactory(
    private val application: Application,
    private val repository: WeatherRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WeatherViewModel(application, repository) as T
    }
}
