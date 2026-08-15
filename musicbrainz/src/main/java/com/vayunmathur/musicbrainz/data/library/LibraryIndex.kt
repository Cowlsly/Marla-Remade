package com.vayunmathur.musicbrainz.data.library

import com.vayunmathur.musicbrainz.data.LocalTrack
import com.vayunmathur.musicbrainz.domain.library.MatchKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An immutable view of what the user already has, built from the scanned tags.
 *
 * Held as sets rather than queried per row because the browse screens ask about every
 * track on a release as they draw it.
 */
class LibrarySnapshot(
    private val recordingIds: Set<String> = emptySet(),
    private val releaseTrackIds: Set<String> = emptySet(),
    private val releaseIds: Set<String> = emptySet(),
    private val trackKeys: Set<String> = emptySet(),
    private val albumKeys: Set<String> = emptySet(),
    val trackCount: Int = 0,
) {
    /**
     * Whether a MusicBrainz track is already on device.
     *
     * The identifier checks come first and are exact. The text fallbacks exist because
     * music that was not tagged by Picard carries no MusicBrainz IDs at all, which is
     * most libraries - without them the app would report almost everything as missing.
     */
    fun hasTrack(
        recordingId: String?,
        releaseTrackId: String?,
        artist: String?,
        album: String?,
        title: String?,
    ): Boolean {
        if (releaseTrackId != null && releaseTrackId in releaseTrackIds) return true
        if (recordingId != null && recordingId in recordingIds) return true
        MatchKeys.trackKey(artist, title)?.let { if (it in trackKeys) return true }
        MatchKeys.albumKey(album, title)?.let { if (it in albumKeys) return true }
        return false
    }

    fun hasRelease(releaseId: String?): Boolean = releaseId != null && releaseId in releaseIds

    companion object {
        val Empty = LibrarySnapshot()

        fun from(tracks: List<LocalTrack>): LibrarySnapshot = LibrarySnapshot(
            recordingIds = tracks.mapNotNullTo(HashSet()) { it.recordingId },
            releaseTrackIds = tracks.mapNotNullTo(HashSet()) { it.releaseTrackId },
            releaseIds = tracks.mapNotNullTo(HashSet()) { it.releaseId },
            trackKeys = tracks.mapNotNullTo(HashSet()) { it.matchKey },
            albumKeys = tracks.mapNotNullTo(HashSet()) { it.albumKey },
            trackCount = tracks.size,
        )
    }
}

/**
 * Process-wide holder for the scanned library.
 *
 * A singleton so the scan worker and the UI share one copy: the scan runs outside the
 * ViewModel's lifetime and the browse screens need the result the moment it lands.
 */
object LibraryIndex {
    private val _snapshot = MutableStateFlow(LibrarySnapshot.Empty)
    val snapshot: StateFlow<LibrarySnapshot> = _snapshot.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    fun publish(tracks: List<LocalTrack>) {
        _snapshot.value = LibrarySnapshot.from(tracks)
    }

    fun setScanning(value: Boolean) {
        _scanning.value = value
    }
}
