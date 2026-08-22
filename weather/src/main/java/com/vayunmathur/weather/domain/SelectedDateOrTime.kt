package com.vayunmathur.weather.domain

sealed interface SelectedDateOrTime {
    data class Time(val isoTime: String) : SelectedDateOrTime
    data class Day(val isoDate: String) : SelectedDateOrTime
}
