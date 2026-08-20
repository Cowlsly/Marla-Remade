package com.vayunmathur.musicbrainz.platform.download

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a queued download is doing right now. */
enum class DownloadState { Queued, Searching, Downloading, Tagging, Done, Failed }

data class DownloadItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val state: DownloadState,
    val progress: Float = 0f,
    val error: String? = null,
)

/** Everything the worker needs to fetch and tag one track. */
data class DownloadRequest(
    val recordingId: String?,
    val releaseTrackId: String?,
    val releaseId: String?,
    val releaseGroupId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val date: String?,
    val trackNumber: Int?,
    val trackTotal: Int?,
    val discNumber: Int?,
    val durationMs: Int?,
    /** The recording's ISRCs, for sources that match on identity rather than a search. */
    val isrcs: List<String> = emptyList(),
) {
    /**
     * Stable identity for the queue and for WorkManager's unique work name.
     *
     * Prefers the release track id so the same recording appearing on two albums is two
     * separate downloads rather than one silently replacing the other.
     */
    val key: String
        get() = releaseTrackId ?: recordingId ?: "$artist\u0000$album\u0000$title"
}

/**
 * Process-wide view of in-flight downloads.
 *
 * WorkManager owns the actual execution and survives process death; this only mirrors
 * progress for the UI, so it is intentionally not persisted.
 */
object DownloadQueue {
    private val _items = MutableStateFlow<Map<String, DownloadItem>>(emptyMap())
    val items: StateFlow<Map<String, DownloadItem>> = _items.asStateFlow()

    fun enqueue(context: Context, request: DownloadRequest) {
        val key = request.key
        if (_items.value[key]?.state in ACTIVE_STATES) return
        _items.update(
            DownloadItem(
                id = key,
                title = request.title,
                artist = request.artist,
                album = request.album,
                state = DownloadState.Queued,
            ),
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            "musicbrainz-download-$key",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(request.toData()).build(),
        )
    }

    fun enqueueAll(context: Context, requests: List<DownloadRequest>) {
        requests.forEach { enqueue(context, it) }
    }

    fun cancel(context: Context, key: String) {
        WorkManager.getInstance(context).cancelUniqueWork("musicbrainz-download-$key")
        _items.value = _items.value - key
    }

    fun clearFinished() {
        _items.value = _items.value.filterValues { it.state in ACTIVE_STATES }
    }

    internal fun update(state: DownloadState, key: String, progress: Float = 0f, error: String? = null) {
        val existing = _items.value[key] ?: return
        _items.update(existing.copy(state = state, progress = progress, error = error))
    }

    private fun MutableStateFlow<Map<String, DownloadItem>>.update(item: DownloadItem) {
        value = value + (item.id to item)
    }

    private val ACTIVE_STATES = setOf(
        DownloadState.Queued,
        DownloadState.Searching,
        DownloadState.Downloading,
        DownloadState.Tagging,
    )
}

internal fun DownloadRequest.toData(): Data = Data.Builder()
    .putString("recordingId", recordingId)
    .putString("releaseTrackId", releaseTrackId)
    .putString("releaseId", releaseId)
    .putString("releaseGroupId", releaseGroupId)
    .putString("title", title)
    .putString("artist", artist)
    .putString("album", album)
    .putString("albumArtist", albumArtist)
    .putString("date", date)
    .putInt("trackNumber", trackNumber ?: 0)
    .putInt("trackTotal", trackTotal ?: 0)
    .putInt("discNumber", discNumber ?: 0)
    .putInt("durationMs", durationMs ?: 0)
    .putStringArray("isrcs", isrcs.toTypedArray())
    .build()

internal fun Data.toDownloadRequest(): DownloadRequest? {
    val title = getString("title") ?: return null
    val artist = getString("artist") ?: return null
    return DownloadRequest(
        recordingId = getString("recordingId"),
        releaseTrackId = getString("releaseTrackId"),
        releaseId = getString("releaseId"),
        releaseGroupId = getString("releaseGroupId"),
        title = title,
        artist = artist,
        album = getString("album"),
        albumArtist = getString("albumArtist"),
        date = getString("date"),
        trackNumber = getInt("trackNumber", 0).takeIf { it > 0 },
        trackTotal = getInt("trackTotal", 0).takeIf { it > 0 },
        discNumber = getInt("discNumber", 0).takeIf { it > 0 },
        durationMs = getInt("durationMs", 0).takeIf { it > 0 },
        isrcs = getStringArray("isrcs")?.toList().orEmpty(),
    )
}
