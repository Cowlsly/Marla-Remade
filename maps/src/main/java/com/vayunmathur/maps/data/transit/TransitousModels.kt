package com.vayunmathur.maps.data.transit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Public-transit models for the P10 departure boards (Transitous / MOTIS,
 * open GTFS + GTFS-RT). Two layers of types live here:
 *
 *  - **App models** ([TransitStop], [Departure]) — what the overlay layer, the
 *    ViewModel and the [com.vayunmathur.maps.ui.DeparturesSheet] consume. They
 *    are position/epoch-based and carry no MOTIS wire quirks.
 *  - **Wire DTOs** (the `Motis*` classes) — the subset of the MOTIS v1 REST
 *    responses we actually read. Every field is optional/defaulted and the
 *    [com.vayunmathur.library.network.NetworkClient] JSON is
 *    `ignoreUnknownKeys`, so a MOTIS schema change degrades to nulls rather
 *    than throwing (mirrors the Google-scrape null-safety convention).
 */

/** A transit stop drawn on the map and tapped to open its departure board. */
data class TransitStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
)

/**
 * One upcoming departure at a stop. Times are epoch millis so the sheet can run
 * a purely client-side live countdown; [delayMinutes] is realtime − scheduled
 * (positive = late, negative = early) and drives the delay colouring.
 */
data class Departure(
    val line: String,
    val headsign: String,
    val scheduledMillis: Long,
    val realtimeMillis: Long,
    val delayMinutes: Int,
    val realTime: Boolean,
    val platform: String?,
    val mode: String?,
    /** GTFS route colour as a 6-digit hex WITHOUT a leading `#`, or null. */
    val routeColor: String?,
    val cancelled: Boolean,
)

// --- MOTIS v1 wire DTOs ----------------------------------------------------

/** Response of `GET /api/v1/map/stops` — the stops within a viewport box. */
@Serializable
data class MotisMapStop(
    val id: String,
    val name: String = "",
    val lat: Double,
    val lon: Double,
)

/** Response of `GET /api/v1/stoptimes` — the live board for one stop. */
@Serializable
data class MotisStoptimes(
    val stopTimes: List<MotisStopTime> = emptyList(),
)

@Serializable
data class MotisStopTime(
    val place: MotisPlace? = null,
    val mode: String? = null,
    val realTime: Boolean = false,
    val headsign: String? = null,
    val agencyName: String? = null,
    val routeShortName: String? = null,
    val routeColor: String? = null,
    val routeTextColor: String? = null,
    val tripId: String? = null,
    val cancelled: Boolean = false,
)

/**
 * A place node inside a MOTIS stoptime. Times are ISO-8601 strings (with a zone
 * offset); `scheduled*` is the timetable time and the bare field is realtime.
 */
@Serializable
data class MotisPlace(
    val name: String? = null,
    val stopId: String? = null,
    val arrival: String? = null,
    val departure: String? = null,
    @SerialName("scheduledArrival") val scheduledArrival: String? = null,
    @SerialName("scheduledDeparture") val scheduledDeparture: String? = null,
    val track: String? = null,
    @SerialName("scheduledTrack") val scheduledTrack: String? = null,
)

// --- MOTIS v1 journey planning (`GET /api/v1/plan`) — P11d online fallback ---

/** Response of `GET /api/v1/plan`: a list of candidate itineraries. */
@Serializable
data class MotisPlanResponse(
    val itineraries: List<MotisItinerary> = emptyList(),
)

/** One end-to-end itinerary; `duration` is whole seconds. */
@Serializable
data class MotisItinerary(
    val duration: Long = 0,
    val legs: List<MotisLeg> = emptyList(),
)

/**
 * One leg of an itinerary. `mode` is `WALK` for walking legs, otherwise a
 * transit mode (`BUS`, `TRAM`, `SUBWAY`, `RAIL`, …). `duration` is whole
 * seconds and `distance` is metres.
 */
@Serializable
data class MotisLeg(
    val mode: String? = null,
    val from: MotisPlanPlace? = null,
    val to: MotisPlanPlace? = null,
    val duration: Long = 0,
    val distance: Double = 0.0,
    val routeShortName: String? = null,
    val routeColor: String? = null,
    val headsign: String? = null,
    val tripId: String? = null,
)

/** A leg endpoint in a plan response (position + name + ISO-8601 times). */
@Serializable
data class MotisPlanPlace(
    val name: String? = null,
    val stopId: String? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val arrival: String? = null,
    val departure: String? = null,
    @SerialName("scheduledArrival") val scheduledArrival: String? = null,
    @SerialName("scheduledDeparture") val scheduledDeparture: String? = null,
)
