package com.vayunmathur.maps.util

/**
 * The UI contract between the ViewModels and the screens that can be rendered without a
 * map.
 *
 * A screen takes a state value plus an actions interface rather than the ViewModels
 * themselves, so it can be rendered by a `@Preview` — which is what the store listing
 * images are generated from. Anything drawn on top of [org.maplibre.compose.map.MaplibreMap]
 * stays as it is; the map is a native surface that a preview cannot render.
 *
 * This lives in `util` rather than `ui` so the dependency runs one way: `ui` depends on
 * `util`.
 */

/** Everything the search screen draws. */
data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val recents: List<String> = emptyList(),
)

/**
 * Search callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 *
 * These are not implemented by [MapsSearchViewModel]: a query needs the visible bounding
 * box (to bias the Google search toward the map centre) and picking a result touches the
 * nav back stack, so the search page builds the adapter itself.
 */
interface SearchActions {
    fun setQuery(query: String) {}
    fun selectResult(result: SearchResult) {}
    /**
     * Handle a postal address picked from a contact (P17): run the Google search
     * for [address] and auto-select the first result — the same path as tapping
     * the top search row, no user tap required. No-op when the search is empty.
     */
    fun pickContactAddress(address: String) {}
    fun clearRecents() {}
    fun back() {}

    companion object {
        val Noop: SearchActions = object : SearchActions {}
    }
}
