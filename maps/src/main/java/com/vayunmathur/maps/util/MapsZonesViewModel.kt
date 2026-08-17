package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Wraps [ZoneDownloadManager] so composables can observe downloaded /
 * downloading zone state without each one re-creating a manager (and the
 * associated polling flows). The actual file/disk work stays in
 * [ZoneDownloadManager].
 */
class MapsZonesViewModel(application: Application) : AndroidViewModel(application) {

    private val zoneManager = ZoneDownloadManager(application)

    val downloadedZones: StateFlow<List<Int>> = zoneManager
        .getDownloadedZonesFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    val downloadingZones: StateFlow<Map<Int, Float>> = zoneManager
        .getDownloadingZonesFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap(),
        )

    /** Status of the single global routing graph (P16), independent of zones. */
    val graphStatus: StateFlow<ZoneDownloadManager.GraphStatus> = zoneManager
        .getGraphStatusFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ZoneDownloadManager.GraphStatus.NOT_STARTED,
        )

    fun getZoneStatus(zoneId: Int): ZoneDownloadManager.ZoneStatus =
        zoneManager.getZoneStatus(zoneId)

    fun startDownload(zoneId: Int) {
        zoneManager.startDownload(zoneId)
    }

    fun deleteZone(zoneId: Int) {
        zoneManager.deleteZone(zoneId)
    }

    fun startGraphDownload() {
        zoneManager.startGraphDownload()
    }

    fun deleteGraph() {
        zoneManager.deleteGraph()
    }
}
