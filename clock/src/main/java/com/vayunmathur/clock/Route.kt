package com.vayunmathur.clock

import com.vayunmathur.library.util.NavKey
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data class Main(val initialTab: Int = 0) : Route
    @Serializable
    data object Alarm : Route
    @Serializable
    data object Clock : Route
    @Serializable
    data object Timer : Route
    @Serializable
    data object Stopwatch : Route
    @Serializable
    data object AlarmSettings : Route
    @Serializable
    data object SelectTimeZonesDialog : Route
    @Serializable
    data class NewTimerDialog(val lengthSeconds: Int? = null, val message: String? = null) : Route
    @Serializable
    data class NewAlarmDialog(val hour: Int? = null, val minutes: Int? = null, val message: String? = null, val days: ArrayList<Int>? = null, val skipUi: Boolean = false) : Route
    @Serializable
    data class AlarmSetTimeDialog(val id: Long, val time: LocalTime) : Route
}
