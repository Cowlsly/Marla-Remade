package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.AddressResult
import com.vayunmathur.maps.data.AmenityRepository
import com.vayunmathur.maps.data.AmenityEntity
import com.vayunmathur.maps.data.OpeningHours
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.geojson.Position

/**
 * A single row in the search list. The one search box returns both nearby named
 * amenities and free-text address matches, so results are a mix of the two.
 */
sealed interface SearchResult {
    val key: String
    val title: String
    val lat: Double
    val lon: Double

    data class Amenity(val entity: AmenityEntity) : SearchResult {
        override val key get() = "amenity:${entity.id}"
        override val title get() = entity.name
        override val lat get() = entity.lat
        override val lon get() = entity.lon
    }

    data class Address(val result: AddressResult) : SearchResult {
        override val key get() = "address:${result.id}"
        override val title get() = result.address
        override val lat get() = result.lat
        override val lon get() = result.lon
    }
}

/**
 * Owns the text query and result list for the maps search page.
 *
 * The viewport bounding box is supplied per-query because it is determined by
 * the camera in the calling composable; we don't observe it as state.
 */
class MapsSearchViewModel(application: Application) : AndroidViewModel(application) {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    /** In-flight search job — cancelled on each new keystroke so old DB queries
     *  don't race ahead of newer ones and overwrite the result list. */
    private var searchJob: Job? = null

    /**
     * Updates the search text and (asynchronously) the result list. Named
     * amenities are matched within the current viewport [west]..[east] /
     * [south]..[north]; addresses are matched globally so you can jump to one
     * that isn't currently on screen. Queries shorter than two characters clear
     * the results without hitting the DB.
     *
     * Debounced ~120 ms so fast typists don't stack a query per keystroke; the
     * previous job is cancelled on every call.
     */
    fun setQuery(
        query: String,
        repository: AmenityRepository,
        west: Double,
        east: Double,
        south: Double,
        north: Double,
    ) {
        _query.value = query
        searchJob?.cancel()
        if (query.length < 2) {
            _results.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(120)
            // Amenities: all prefix tokens must match (AND) so "blue bot" ->
            // "Blue Bottle". Addresses: any prefix token may match (OR) and
            // bm25 ranks the closest, giving fuzzy "closest match" behaviour.
            val amenityQuery = ftsPrefixQuery(query, " ")
            val addressQuery = ftsPrefixQuery(query, " OR ")
            if (amenityQuery.isEmpty()) {
                _results.value = emptyList()
                return@launch
            }
            val merged = withContext(Dispatchers.IO) {
                val amenities = repository.getInBBox(
                    query = amenityQuery,
                    latMin = south,
                    latMax = north,
                    lonMin = west,
                    lonMax = east,
                ).map { SearchResult.Amenity(it) }
                val addresses = repository.searchAddresses(addressQuery)
                    .map { SearchResult.Address(it) }
                amenities + addresses
            }
            _results.value = merged
        }
    }

    /** Resets the search state. */
    fun setQuery(
        query: String,
        db: com.vayunmathur.maps.data.AmenityDatabase,
        west: Double,
        east: Double,
        south: Double,
        north: Double,
    ) = setQuery(query, AmenityRepository.get(getApplication()), west, east, south, north)

    fun resolveAmenity(
        amenity: AmenityEntity,
        db: com.vayunmathur.maps.data.AmenityDatabase,
        onFeature: (SpecificFeature\.Restaurant\) -> Unit,
    ) = resolveAmenity(amenity, AmenityRepository.get(getApplication()), onFeature)

    fun reset() {
        searchJob?.cancel()
        _query.value = ""
        _results.value = emptyList()
    }

    /**
     * Loads tags for [amenity], builds a [SpecificFeature.Restaurant], and
     * invokes [onFeature] back on the main thread. The launch runs in
     * `viewModelScope` so a navigation-away mid-resolve cleanly cancels the
     * callback rather than calling back into a stale UI.
     */
    fun resolveAmenity(
        amenity: AmenityEntity,
        repository: AmenityRepository,
        onFeature: (SpecificFeature.Restaurant) -> Unit,
    ) {
        viewModelScope.launch {
            val tags = withContext(Dispatchers.IO) {
                repository.getTags(amenity.id).associate { it.key to it.value }
            }
            val feature = SpecificFeature.Restaurant(
                name = tags["name"] ?: "",
                phone = tags["phone"],
                website = tags["website"],
                menu = tags["website:menu"],
                openingHours = tags["opening_hours"]?.let { OpeningHours.from(it) },
                position = Position(amenity.lon, amenity.lat),
            )
            onFeature(feature)
        }
    }

    /**
     * Builds a [SpecificFeature.GenericPlace] for an address hit. Addresses
     * carry no OSM tags, so this is a plain place (name = the address string)
     * that flows through the same selection path as an amenity.
     */
    fun addressFeature(address: AddressResult): SpecificFeature.GenericPlace =
        SpecificFeature.GenericPlace(
            name = address.address,
            phone = null,
            website = null,
            openingHours = null,
            position = Position(address.lon, address.lat),
        )
}

/**
 * Turns free-text into an FTS5 expression. Splits on non-alphanumeric
 * characters, lowercases, and turns each token into a prefix term (`market*`),
 * joined by [joiner] (" " for AND, " OR " for OR). Returns "" when there are no
 * usable tokens.
 */
private fun ftsPrefixQuery(raw: String, joiner: String): String {
    val tokens = raw.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }
    if (tokens.isEmpty()) return ""
    return tokens.joinToString(joiner) { "$it*" }
}
