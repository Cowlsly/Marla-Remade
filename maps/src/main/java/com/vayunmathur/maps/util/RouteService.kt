package com.vayunmathur.maps.util

import kotlinx.serialization.Serializable
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object RouteService {
    enum class TravelMode { DRIVE, TRANSIT, WALK, BICYCLE }
    object API {
        @Serializable data class TransitDetails(val headsign: String, val stopCount: Int, val transitLine: TransitLine, val stopDetails: StopDetails, val feedName: String? = null)
        @Serializable data class StopDetails(val arrivalTime: String, val departureTime: String, val arrivalStop: Stop, val departureStop: Stop)
        @Serializable data class Stop(val name: String)
        @Serializable data class TransitLine(val name: String, val nameShort: String? = null, val color: String)
        @Serializable data class NavInstruction(val maneuver: Maneuver = Maneuver.MANEUVER_UNSPECIFIED, val instructions: String = "")
        @Serializable enum class Maneuver { MANEUVER_UNSPECIFIED, TURN_SLIGHT_LEFT, TURN_SHARP_LEFT, UTURN_LEFT, TURN_LEFT, TURN_SLIGHT_RIGHT, TURN_SHARP_RIGHT, UTURN_RIGHT, TURN_RIGHT, STRAIGHT, RAMP_LEFT, RAMP_RIGHT, MERGE, FORK_LEFT, FORK_RIGHT, FERRY, FERRY_TRAIN, ROUNDABOUT_LEFT, ROUNDABOUT_RIGHT, DEPART, NAME_CHANGE, WAIT, RIDE }
        /**
         * One available turn lane at the maneuver's junction, ordered left→right.
         * [directions] are the turn indications the lane offers — real OSM
         * `turn:lanes` can list several per lane (e.g. through + right), falling
         * back to a single topology-inferred direction when tags are absent.
         * [active] marks a lane that leads onto the taken route (highlighted in
         * the lane-guidance UI).
         */
        @Serializable data class Lane(val directions: List<Maneuver>, val active: Boolean)
    }
    data class Route(override val duration: Duration, override val distanceMeters: Double, val polyline: List<Position>, val step: List<Step>): RouteType
    data class Step(val distanceMeters: Double, val staticDuration: Duration, val polyline: List<Position>, val navInstruction: API.NavInstruction, val travelMode: TravelMode, val transitDetails: API.TransitDetails? = null, val speedRatio: Double = 1.0, val lanes: List<API.Lane> = emptyList())
    interface RouteType { val duration: Duration; val distanceMeters: Double }
    class EmptyRoute: RouteType { override val duration: Duration = 0.seconds; override val distanceMeters: Double = 0.0 }
}
