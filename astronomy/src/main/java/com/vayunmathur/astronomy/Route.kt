package com.vayunmathur.astronomy

import com.vayunmathur.library.util.NavKey
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable data object SkyMap : Route
    @Serializable data class ObjectDetail(val id: String) : Route
    @Serializable data object Search : Route
    @Serializable data object Settings : Route
    @Serializable data class HistoryDatePicker(val initialDate: LocalDate) : Route
}