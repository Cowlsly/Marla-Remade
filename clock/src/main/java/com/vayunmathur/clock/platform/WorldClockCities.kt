package com.vayunmathur.clock.platform

import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The cities on the world clock list, in the order the user dragged them into.
 *
 * Stored as one newline-joined string: this replaced a `Set<String>` under [LEGACY_KEY], which
 * couldn't hold an order. Commas are not safe as the separator because city names contain them.
 */
object WorldClockCities {
    private const val KEY = "time_zones_order"
    private const val LEGACY_KEY = "time_zones"

    fun flow(ds: DataStoreUtils): Flow<List<String>> = ds.stringFlow(KEY).map(::decode)

    suspend fun set(ds: DataStoreUtils, cities: List<String>) {
        ds.setString(KEY, encode(cities))
    }

    suspend fun toggle(ds: DataStoreUtils, city: String) {
        ds.updateString(KEY) { stored ->
            val cities = decode(stored)
            encode(if (city in cities) cities - city else cities + city)
        }
    }

    /** One-time move off the unordered [LEGACY_KEY] set, seeding the order alphabetically. */
    suspend fun migrate(ds: DataStoreUtils) {
        if (ds.getStringAwait(KEY) != null) return
        set(ds, ds.getStringSetAwait(LEGACY_KEY).sorted())
        ds.removeKeys(listOf(LEGACY_KEY))
    }

    private fun encode(cities: List<String>) = cities.joinToString("\n")

    private fun decode(stored: String?): List<String> =
        stored?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
}
