package com.vayunmathur.weather.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * A place the user has pinned in the weather app — either added by city
 * search ([isCurrent] = false) or auto-tracked from the device location
 * ([isCurrent] = true, single-row constraint enforced at the DAO level).
 *
 * [latitude] / [longitude] are what the Open-Meteo forecast endpoint accepts
 * directly. [name] / [country] are what the UI shows; for the current-device
 * row [name] is left blank until reverse geocoding fills it in.
 */
@Entity
data class SavedLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val country: String = "",
    val latitude: Double,
    val longitude: Double,
    val displayOrder: Int = 0,
    val isCurrent: Boolean = false,
)
