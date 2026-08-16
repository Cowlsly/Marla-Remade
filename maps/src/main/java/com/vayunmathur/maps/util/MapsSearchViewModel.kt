package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.RecentSearchStore
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GoogleSearchDataSource
import com.vayunmathur.maps.data.google.GoogleSearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.maplibre.spatialk.geojson.Position

/**
 * A single row in the search list. Since search is now Google-only (Decision D2,
 * amenities.db removed), every result is one Google place: a title, an optional
 * subtitle (address or category) and a position, so it can both fill the list
 * row and drop a pin on the map.
 */
data class SearchResult(
    val id: String,
    val title: String,
    val subtitle: String?,
    val lat: Double,
    val lon: Double,
    val category: String?,
)

private fun GoogleSearchResult.toSearchResult() = SearchResult(
    id = id,
    title = name,
    subtitle = address ?: category,
    lat = lat,
    lon = lng,
    category = category,
)

/**
 * Owns the text query and result list for the maps search page.
 *
 * Results come from the keyless Google scrape ([GoogleSearchDataSource]); the map
 * centre is supplied per-query by the calling composable to bias the search. The
 * results flow is also observed by `MapPage` to draw the search-result pin layer.
 */
class MapsSearchViewModel(application: Application) : AndroidViewModel(application) {

    private val recentStore = RecentSearchStore.get(application)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    /** Recent queries, most-recent first, for the pre-search suggestions. */
    val recents: StateFlow<List<String>> = recentStore.recents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), recentStore.current())

    /** In-flight search job — cancelled on each new keystroke so an older scrape
     *  doesn't race ahead of a newer one and overwrite the result list. */
    private var searchJob: Job? = null

    /**
     * Updates the search text and (asynchronously) the result list via the Google
     * scrape, biased toward [nearLat],[nearLon] (the map centre). Queries shorter
     * than two characters clear the results without hitting the network.
     *
     * Debounced ~250 ms (Decision D7) so fast typists don't stack a scrape per
     * keystroke; the previous job is cancelled on every call.
     */
    fun setQuery(query: String, nearLat: Double, nearLon: Double) {
        _query.value = query
        searchJob?.cancel()
        if (query.length < 2) {
            _results.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            // GoogleSearchDataSource.search does its own Dispatchers.IO + never
            // throws (empty list on any scrape/path failure).
            _results.value = GoogleSearchDataSource.search(query, nearLat, nearLon)
                .map { it.toSearchResult() }
        }
    }

    /** Resets the search state (keeps recents). */
    fun reset() {
        searchJob?.cancel()
        _query.value = ""
        _results.value = emptyList()
    }

    /** Record a committed query in the recents store. */
    fun recordRecent(query: String) {
        viewModelScope.launch { recentStore.add(query) }
    }

    fun clearRecents() {
        viewModelScope.launch { recentStore.clear() }
    }

    /**
     * Build a [SpecificFeature.GenericPlace] for a picked search result. It flows
     * through the same selection path as a tapped map pin, so
     * `SelectedFeatureViewModel.currentPoiInfo` then fetches the Google
     * enrichment for the sheet.
     */
    fun toFeature(result: SearchResult): SpecificFeature.GenericPlace =
        SpecificFeature.GenericPlace(
            name = result.title,
            phone = null,
            website = null,
            openingHours = null,
            position = Position(result.lon, result.lat),
        )

    /**
     * Reverse-geocode [lat],[lon] to the nearest addressable place and hand it
     * back as a [SpecificFeature.GenericPlace] (or null when there's no match).
     * Replaces the removed address FTS geocoder; used for "what's here?" taps on
     * empty map (Decision D2 — online-only).
     */
    fun reverseGeocode(lat: Double, lon: Double, onResult: (SpecificFeature.GenericPlace?) -> Unit) {
        viewModelScope.launch {
            val hit = GoogleSearchDataSource.reverseGeocode(lat, lon)
            onResult(
                hit?.let {
                    SpecificFeature.GenericPlace(
                        name = it.name.ifBlank { it.address ?: "" },
                        phone = null,
                        website = null,
                        openingHours = null,
                        position = Position(it.lng, it.lat),
                    )
                }
            )
        }
    }
}
