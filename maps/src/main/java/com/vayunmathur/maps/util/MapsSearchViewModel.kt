package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.RecentSearchStore
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GoogleSearchDataSource
import com.vayunmathur.maps.data.google.GoogleSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private fun PoiIndex.PoiRecord.toSearchResult() = SearchResult(
    id = "poi:$latE7:$lonE7:${name.hashCode()}",
    title = name,
    subtitle = PoiCategories.label(type),
    lat = lat,
    lon = lon,
    category = PoiCategories.label(type),
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
            // Try the offline OSM POI index first (P27): resolving a POI name
            // locally avoids a Google call. Google stays the fallback (and
            // handles addresses, which the POI index doesn't carry). Any offline
            // failure (unmapped/poisoned index, decode error) is swallowed so the
            // query still falls through to Google.
            val app = getApplication<Application>()
            val offline = withContext(Dispatchers.IO) {
                runCatching {
                    PoiIndex.initialize(app)
                    PoiIndex.searchByName(query, nearLat, nearLon, limit = 20)
                }.getOrDefault(emptyList())
            }
            if (offline.isNotEmpty()) {
                _results.value = offline.map { it.toSearchResult() }
                return@launch
            }
            // GoogleSearchDataSource.search does its own Dispatchers.IO + never
            // throws (empty list on any scrape/path failure).
            _results.value = GoogleSearchDataSource.search(query, nearLat, nearLon)
                .map { it.toSearchResult() }
        }
    }

    /**
     * Runs the Google search for [query] immediately (no debounce), publishes the
     * results so the list + map pins update, and hands back the FIRST result (or
     * null) so a caller can auto-select it programmatically.
     *
     * Used by the P17 contact-address shortcut: pick an address → search → open
     * the top hit without a user tap. Cancels any in-flight keystroke search
     * first so results don't race.
     */
    fun searchAndSelectFirst(
        query: String,
        nearLat: Double,
        nearLon: Double,
        onFirst: (SearchResult?) -> Unit,
    ) {
        _query.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // GoogleSearchDataSource.search does its own Dispatchers.IO + never
            // throws (empty list on any scrape/path failure).
            val results = GoogleSearchDataSource.search(query, nearLat, nearLon)
                .map { it.toSearchResult() }
            _results.value = results
            onFirst(results.firstOrNull())
        }
    }

    /**
     * Resolve a free-text [address] (e.g. a picked contact postal address) to a
     * single place and hand it back — WITHOUT touching the query text or the
     * result list. The contact-address shortcut (P17/P31) opens the place
     * directly instead of going through the search box, so this must NOT set
     * [_query] or populate [_results] (that would prefill the search bar and
     * draw the search-result pins).
     *
     * A full address string is resolved via the Google text [GoogleSearchDataSource.search]
     * (the only path that maps a string to a coordinate; [reverseGeocode] takes a
     * coordinate, not an address). The search does its own [Dispatchers.IO] and
     * never throws; [onResolved] fires on the main thread so the caller can
     * safely drive [SelectedFeatureViewModel.selectAndFocus]. Returns null when
     * nothing resolves.
     */
    fun resolveAndSelect(
        address: String,
        nearLat: Double,
        nearLon: Double,
        onResolved: (SpecificFeature.GenericPlace?) -> Unit,
    ) {
        if (address.isBlank()) {
            onResolved(null)
            return
        }
        viewModelScope.launch {
            val hit = GoogleSearchDataSource.search(address, nearLat, nearLon).firstOrNull()
            onResolved(
                hit?.let {
                    SpecificFeature.GenericPlace(
                        name = it.name.ifBlank { it.address ?: address },
                        phone = null,
                        website = null,
                        openingHours = null,
                        position = Position(it.lng, it.lat),
                    )
                }
            )
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
