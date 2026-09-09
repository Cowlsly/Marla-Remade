package com.vayunmathur.maps.data.google

/**
 * Clean, null-safe model of the POI enrichment scraped from Google Maps' keyless
 * web endpoints. Every field is nullable/optional because no single response
 * fills all of them (a keyless response is bot-degraded — popular times and the
 * full photo gallery are usually stripped), and because the scrape is fragile:
 * a Google-side reshape makes individual fields go null while the rest survive.
 *
 * The feature degrades gracefully — a section simply doesn't render when its
 * field is empty — and the parse never throws.
 */
data class GooglePoiInfo(
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val priceText: String? = null,      // Google's own label, e.g. "$10–20" / "$$"
    val priceLevel: Int? = null,        // derived 1..4
    val website: String? = null,
    val phone: String? = null,
    val openNow: Boolean? = null,
    val statusText: String? = null,     // e.g. "Open · Closes 9 PM"
    val category: String? = null,
    val editorialSummary: String? = null,
    val featuredReview: String? = null,
    val fuelPrice: FuelPrice? = null,   // gas stations only
    // Only ever today, e.g. ["Tuesday: 6 AM–10 PM"] — the search response carries no
    // weekly schedule (calibrated 2026-09; see readHours in GooglePoiDataSource).
    val hours: List<String> = emptyList(),
    val photoUrls: List<String> = emptyList(),  // FIFE image URLs, resized for thumbnails
    val reviews: List<GoogleReview> = emptyList(),
    val popularTimes: PoiPopularTimes? = null,
    val featureId: String? = null,      // "0x..:0x.." → reviews RPC
) {
    /** True when nothing worth showing came back — the UI shows no enrichment. */
    val isEmpty: Boolean
        get() = rating == null && reviewCount == null && priceText == null && website == null &&
            phone == null && statusText == null && editorialSummary == null && featuredReview == null &&
            fuelPrice == null &&
            hours.isEmpty() && photoUrls.isEmpty() && reviews.isEmpty() && popularTimes == null
}

/**
 * A fuel pump price, e.g. [price] "$6.20", [grade] "Regular".
 *
 * Google sends one grade per station and formats it itself, so [price] keeps the
 * currency symbol as given rather than being reduced to a number — the scrape has
 * no locale to re-format it with. Across a 20-station US sample the grade was
 * always "Regular"; it is kept as a string because nothing guarantees that.
 */
data class FuelPrice(val price: String, val grade: String)

/** A single user review. [rating] is 1..5; [text] is null for rating-only reviews. */
data class GoogleReview(
    val author: String,
    val authorPhoto: String?,
    val rating: Int,
    val relativeTime: String?,
    val text: String?,
    val photos: List<String> = emptyList(),
)

/** Google's "popular times": a typical-busyness histogram per day of the week. */
data class PoiPopularTimes(val days: List<PoiDayBusyness>)

/** One day: [dayOfWeek] is 1=Mon … 7=Sun; [hours] are the busyness buckets. */
data class PoiDayBusyness(val dayOfWeek: Int, val hours: List<PoiHourBusyness>)

/** [hour] is 0..23; [occupancy] is the typical busyness 0..100. */
data class PoiHourBusyness(val hour: Int, val occupancy: Int)

/**
 * One tab's worth of the enrichment. The place sheet shows exactly one of these at a
 * time, so what used to be three stacked sections is now three destinations.
 *
 * Here rather than beside the composable that renders it because the selected section
 * outlives that composable: it is held on `SelectedFeatureViewModel` so the tab row (in
 * the sheet's measured header) and the panel it switches (below the fold) can agree, and
 * a view model naming a type from the `ui` package would invert the module's layering.
 */
enum class PoiSection { DETAILS, PHOTOS, REVIEWS }
