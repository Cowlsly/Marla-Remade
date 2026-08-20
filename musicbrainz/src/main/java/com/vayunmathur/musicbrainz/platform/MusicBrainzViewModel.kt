package com.vayunmathur.musicbrainz.platform

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.musicbrainz.data.library.LibraryIndex
import com.vayunmathur.musicbrainz.data.library.LibraryScanner
import com.vayunmathur.musicbrainz.data.library.LibrarySnapshot
import com.vayunmathur.musicbrainz.data.tidal.TidalAuth
import com.vayunmathur.musicbrainz.data.tidal.TidalPollResult
import com.vayunmathur.musicbrainz.network.api.CoverArt
import com.vayunmathur.musicbrainz.network.api.MbRecording
import com.vayunmathur.musicbrainz.network.api.MbRelease
import com.vayunmathur.musicbrainz.network.api.MbReleaseGroup
import com.vayunmathur.musicbrainz.network.api.MusicBrainzApi
import com.vayunmathur.musicbrainz.network.api.display
import com.vayunmathur.musicbrainz.platform.download.DownloadQueue
import com.vayunmathur.musicbrainz.platform.download.DownloadRequest
import com.vayunmathur.musicbrainz.platform.SafTree
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * Drives the browse, search and download screens.
 *
 * Fetched pages are cached by MusicBrainz id: the API allows one request per second, so
 * without a cache going back a screen would stall for a visible beat before redrawing
 * something the app already had.
 */
class MusicBrainzViewModel(application: Application) : AndroidViewModel(application), MusicBrainzActions {

    private val prefs = MusicBrainzPrefs(application)

    private val _search = MutableStateFlow(SearchUiState())
    private val _artist = MutableStateFlow(ArtistUiState())
    private val _releaseGroup = MutableStateFlow(ReleaseGroupUiState())
    private val _release = MutableStateFlow(ReleaseUiState())
    private val _tidalLogin = MutableStateFlow(TidalLoginUiState())

    private val artistCache = HashMap<String, ArtistUiState>()
    private val releaseGroupCache = HashMap<String, ReleaseGroupUiState>()
    private val releaseCache = HashMap<String, MbRelease>()

    private var searchJob: Job? = null
    private var tidalLoginJob: Job? = null
    private var loadedReleaseId: String? = null

    /**
     * Search results recombined whenever the library changes, so a track downloaded from
     * one screen immediately shows as owned on another.
     */
    val search: StateFlow<SearchUiState> =
        combine(_search, LibraryIndex.snapshot) { state, library ->
            state.copy(
                recordings = state.recordings.map { it.copy(onDevice = library.matches(it)) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    val artist: StateFlow<ArtistUiState> = _artist.asStateFlow()

    val tidalLogin: StateFlow<TidalLoginUiState> = _tidalLogin.asStateFlow()

    val releaseGroup: StateFlow<ReleaseGroupUiState> =
        combine(_releaseGroup, LibraryIndex.snapshot) { state, library ->
            state.copy(
                releases = state.releases.map { it.copy(onDevice = library.hasRelease(it.id)) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReleaseGroupUiState())

    val release: StateFlow<ReleaseUiState> =
        combine(_release, LibraryIndex.snapshot, DownloadQueue.items) { state, library, downloads ->
            state.copy(
                tracks = state.tracks.map { track ->
                    track.copy(
                        onDevice = library.hasTrack(
                            recordingId = track.recordingId,
                            releaseTrackId = track.releaseTrackId,
                            artist = track.artist,
                            album = state.title,
                            title = track.title,
                        ),
                        download = downloads[track.downloadKey()],
                    )
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReleaseUiState())

    val downloads: StateFlow<Map<String, com.vayunmathur.musicbrainz.platform.download.DownloadItem>> =
        DownloadQueue.items

    /**
     * The Tidal prefs, folded into one flow first: [combine]'s largest typed overload takes
     * five sources and the folder/library group already fills it.
     */
    private val tidalSettings = combine(
        prefs.downloadSource,
        prefs.tidalQuality,
        prefs.tidalAccount,
    ) { source, quality, account ->
        // Falls back to the user id so an account whose payload carried no username still
        // reads as signed in, and so its sign-out row stays reachable.
        Triple(source, quality, account?.let { it.username.ifBlank { it.userId } })
    }

    val settings: StateFlow<SettingsUiState> = combine(
        prefs.musicFolder,
        prefs.fetchLyrics,
        prefs.embedCoverArt,
        LibraryIndex.scanning,
        LibraryIndex.snapshot,
    ) { folder, lyrics, cover, scanning, library ->
        SettingsUiState(
            folderName = folder?.let { readableFolderName(it) },
            scanning = scanning,
            indexedTracks = library.trackCount,
            fetchLyrics = lyrics,
            embedCoverArt = cover,
        )
    }.combine(tidalSettings) { state, (source, quality, username) ->
        state.copy(downloadSource = source, tidalQuality = quality, tidalUsername = username)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch { LibraryScanner.loadCached(getApplication()) }
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    override fun onQueryChange(query: String) {
        _search.value = _search.value.copy(query = query)
    }

    override fun onTabChange(tab: SearchTab) {
        _search.value = _search.value.copy(tab = tab)
        if (_search.value.query.isNotBlank()) search()
    }

    override fun search() {
        val query = _search.value.query.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _search.value = _search.value.copy(loading = true, error = null, hasSearched = true)
            try {
                when (_search.value.tab) {
                    SearchTab.Artists -> {
                        val results = MusicBrainzApi.searchArtists(query)
                        _search.value = _search.value.copy(
                            loading = false,
                            artists = results.map { artist ->
                                ArtistRow(
                                    id = artist.id,
                                    name = artist.name,
                                    subtitle = listOfNotNull(
                                        artist.disambiguation?.takeIf { it.isNotBlank() },
                                        artist.type,
                                        artist.country,
                                    ).joinToString(" \u00B7 ").ifEmpty { null },
                                )
                            },
                        )
                    }
                    SearchTab.Releases -> {
                        val results = MusicBrainzApi.searchReleaseGroups(query)
                        _search.value = _search.value.copy(
                            loading = false,
                            releaseGroups = results.map { it.toRow() },
                        )
                    }
                    SearchTab.Recordings -> {
                        val results = MusicBrainzApi.searchRecordings(query)
                        _search.value = _search.value.copy(
                            loading = false,
                            recordings = results.map { it.toRow() },
                        )
                    }
                }
            } catch (e: Exception) {
                _search.value = _search.value.copy(loading = false, error = e.readableMessage())
            }
        }
    }

    // ------------------------------------------------------------------
    // Browse
    // ------------------------------------------------------------------

    override fun loadArtist(id: String) {
        artistCache[id]?.let {
            _artist.value = it
            return
        }
        _artist.value = ArtistUiState(loading = true)
        viewModelScope.launch {
            try {
                val artist = MusicBrainzApi.artist(id)
                val groups = MusicBrainzApi.releaseGroupsOfArtist(id)
                val state = ArtistUiState(
                    loading = false,
                    name = artist.name,
                    subtitle = listOfNotNull(
                        artist.disambiguation?.takeIf { it.isNotBlank() },
                        artist.type,
                        artist.area?.name,
                        artist.lifeSpan?.begin,
                    ).joinToString(" \u00B7 ").ifEmpty { null },
                    // Newest first, which is how a discography is usually read.
                    releaseGroups = groups
                        .sortedByDescending { it.firstReleaseDate.orEmpty() }
                        .map { it.toRow(includeArtist = false) },
                )
                artistCache[id] = state
                _artist.value = state
            } catch (e: Exception) {
                _artist.value = ArtistUiState(loading = false, error = e.readableMessage())
            }
        }
    }

    override fun loadReleaseGroup(id: String) {
        releaseGroupCache[id]?.let {
            _releaseGroup.value = it
            return
        }
        _releaseGroup.value = ReleaseGroupUiState(loading = true)
        viewModelScope.launch {
            try {
                val group = MusicBrainzApi.releaseGroup(id)
                val releases = MusicBrainzApi.releasesOfReleaseGroup(id)
                val state = ReleaseGroupUiState(
                    loading = false,
                    title = group.title,
                    artist = group.artistCredit.display().orEmpty(),
                    coverUrl = CoverArt.releaseGroup(id),
                    // Official pressings first: they are what a user normally wants, and a
                    // release group can carry dozens of promos and bootlegs otherwise.
                    releases = releases
                        .sortedWith(
                            compareByDescending<com.vayunmathur.musicbrainz.network.api.MbReleaseSummary> {
                                it.status == "Official"
                            }.thenBy { it.date.orEmpty() },
                        )
                        .map { summary ->
                            ReleaseRow(
                                id = summary.id,
                                title = summary.title,
                                subtitle = listOfNotNull(
                                    summary.date?.takeIf { it.isNotBlank() },
                                    summary.country,
                                    summary.media.firstOrNull()?.format,
                                    summary.media.sumOf { it.trackCount }
                                        .takeIf { it > 0 }
                                        ?.let { "$it tracks" },
                                    summary.disambiguation?.takeIf { it.isNotBlank() },
                                ).joinToString(" \u00B7 ").ifEmpty { null },
                                coverUrl = CoverArt.release(summary.id),
                                fallbackCoverUrl = CoverArt.releaseGroup(id),
                            )
                        },
                )
                releaseGroupCache[id] = state
                _releaseGroup.value = state
            } catch (e: Exception) {
                _releaseGroup.value = ReleaseGroupUiState(loading = false, error = e.readableMessage())
            }
        }
    }

    override fun loadRelease(id: String) {
        loadedReleaseId = id
        releaseCache[id]?.let {
            _release.value = it.toUiState()
            return
        }
        _release.value = ReleaseUiState(loading = true)
        viewModelScope.launch {
            try {
                val release = MusicBrainzApi.release(id)
                releaseCache[id] = release
                _release.value = release.toUiState()
            } catch (e: Exception) {
                _release.value = ReleaseUiState(loading = false, error = e.readableMessage())
            }
        }
    }

    // ------------------------------------------------------------------
    // Downloads
    // ------------------------------------------------------------------

    override fun downloadTrack(track: TrackRow) {
        val release = loadedReleaseId?.let { releaseCache[it] } ?: return
        requestFor(release, track)?.let { DownloadQueue.enqueue(getApplication(), it) }
    }

    override fun downloadRelease() {
        val release = loadedReleaseId?.let { releaseCache[it] } ?: return
        val missing = _release.value.tracks.filterNot { it.onDevice }
        DownloadQueue.enqueueAll(
            getApplication(),
            missing.mapNotNull { requestFor(release, it) },
        )
    }

    override fun downloadRecording(recording: RecordingRow) {
        DownloadQueue.enqueue(
            getApplication(),
            DownloadRequest(
                recordingId = recording.id,
                releaseTrackId = null,
                releaseId = recording.releaseId,
                releaseGroupId = recording.releaseGroupId,
                title = recording.title,
                artist = recording.artist,
                album = recording.album,
                albumArtist = recording.artist,
                date = null,
                trackNumber = null,
                trackTotal = null,
                discNumber = null,
                durationMs = recording.durationMs,
            ),
        )
    }

    override fun cancelDownload(id: String) = DownloadQueue.cancel(getApplication(), id)

    override fun clearFinishedDownloads() = DownloadQueue.clearFinished()

    private fun requestFor(release: MbRelease, track: TrackRow): DownloadRequest? {
        val medium = release.media.firstOrNull { medium ->
            medium.tracks.any { it.id == track.releaseTrackId }
        } ?: return null
        return DownloadRequest(
            recordingId = track.recordingId,
            releaseTrackId = track.releaseTrackId,
            releaseId = release.id,
            releaseGroupId = release.releaseGroup?.id,
            title = track.title,
            artist = track.artist,
            album = release.title,
            albumArtist = release.artistCredit.display() ?: track.artist,
            date = release.effectiveDate(),
            trackNumber = track.position,
            trackTotal = medium.trackCount.takeIf { it > 0 },
            discNumber = track.discNumber.takeIf { release.media.size > 1 },
            durationMs = track.durationMs,
            isrcs = track.isrcs,
        )
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    override fun setMusicFolder(uri: String) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri.toUri(), flags)
        }
        viewModelScope.launch {
            prefs.setMusicFolder(uri)
            LibraryScanner.scan(getApplication())
        }
    }

    override fun rescanLibrary() {
        viewModelScope.launch { LibraryScanner.scan(getApplication()) }
    }

    override fun setFetchLyrics(value: Boolean) {
        viewModelScope.launch { prefs.setFetchLyrics(value) }
    }

    override fun setEmbedCoverArt(value: Boolean) {
        viewModelScope.launch { prefs.setEmbedCoverArt(value) }
    }

    override fun setDownloadSource(source: DownloadSource) {
        viewModelScope.launch { prefs.setDownloadSource(source) }
    }

    override fun setTidalQuality(quality: TidalQuality) {
        viewModelScope.launch { prefs.setTidalQuality(quality) }
    }

    override fun signOutOfTidal() {
        viewModelScope.launch {
            prefs.tidalAccount.first()?.accessToken?.takeIf { it.isNotBlank() }?.let {
                TidalAuth.logout(it)
            }
            prefs.clearTidalAccount()
            // The login screen pops itself on Success, so a stale Success left here would
            // pop the next sign-in before the user ever sees a code.
            _tidalLogin.value = TidalLoginUiState()
        }
    }

    // ------------------------------------------------------------------
    // Tidal sign-in
    // ------------------------------------------------------------------

    /**
     * Runs the device-code flow: ask for a code, show it, then poll until the user has
     * entered it elsewhere or the code's own deadline passes.
     */
    fun startTidalLogin() {
        tidalLoginJob?.cancel()
        _tidalLogin.value = TidalLoginUiState()
        tidalLoginJob = viewModelScope.launch {
            val code = try {
                TidalAuth.requestDeviceCode()
            } catch (e: Exception) {
                _tidalLogin.value = TidalLoginUiState(
                    status = TidalLoginStatus.Failed,
                    error = e.readableMessage(),
                )
                return@launch
            }
            _tidalLogin.value = TidalLoginUiState(
                status = TidalLoginStatus.AwaitingUser,
                userCode = code.userCode,
                verificationUri = code.verificationUri,
            )

            val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
            var intervalMs = code.intervalSeconds.coerceAtLeast(1) * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(intervalMs)
                when (val result = TidalAuth.poll(code.deviceCode)) {
                    TidalPollResult.Pending -> Unit
                    TidalPollResult.SlowDown -> intervalMs += SLOW_DOWN_STEP_MS
                    TidalPollResult.Expired -> {
                        failTidalLogin(null)
                        return@launch
                    }
                    is TidalPollResult.Error -> {
                        failTidalLogin(result.message)
                        return@launch
                    }
                    is TidalPollResult.Success -> {
                        val tokens = result.tokens
                        // A blank token would store an account that reports as signed in but
                        // silently resolves nothing, so treat it as a failed sign-in.
                        if (tokens.accessToken.isBlank()) {
                            failTidalLogin(null)
                            return@launch
                        }
                        prefs.setTidalAccount(
                            TidalAccount(
                                accessToken = tokens.accessToken,
                                refreshToken = tokens.refreshToken,
                                expiresAtMs = tokens.expiresAtMs,
                                countryCode = tokens.countryCode,
                                userId = tokens.userId,
                                username = tokens.username,
                            ),
                        )
                        _tidalLogin.value = TidalLoginUiState(status = TidalLoginStatus.Success)
                        return@launch
                    }
                }
            }
            failTidalLogin(null)
        }
    }

    fun cancelTidalLogin() {
        tidalLoginJob?.cancel()
        tidalLoginJob = null
        _tidalLogin.value = TidalLoginUiState()
    }

    private fun failTidalLogin(message: String?) {
        _tidalLogin.value = TidalLoginUiState(
            status = TidalLoginStatus.Failed,
            error = message?.takeIf { it.isNotBlank() },
        )
    }

    // ------------------------------------------------------------------

    /**
     * The release's own date, or the release-group's first-release date when the pressing
     * carries none. Many individual releases have a blank date even though the group has a
     * year on file, and `release(id)` already includes the group, so this fills the gap.
     */
    private fun MbRelease.effectiveDate(): String? =
        date?.takeIf { it.isNotBlank() } ?: releaseGroup?.firstReleaseDate?.takeIf { it.isNotBlank() }

    private fun MbRelease.toUiState() = ReleaseUiState(
        loading = false,
        id = id,
        title = title,
        artist = artistCredit.display().orEmpty(),
        subtitle = listOfNotNull(
            effectiveDate()?.takeIf { it.isNotBlank() },
            status,
            media.firstOrNull()?.format,
            media.sumOf { it.trackCount }.takeIf { it > 0 }?.let { "$it tracks" },
        ).joinToString(" \u00B7 ").ifEmpty { null },
        coverUrl = CoverArt.release(id),
        fallbackCoverUrl = releaseGroup?.id?.let { CoverArt.releaseGroup(it) },
        tracks = media.flatMap { medium ->
            medium.tracks.map { track ->
                TrackRow(
                    releaseTrackId = track.id,
                    recordingId = track.recording?.id,
                    position = track.position,
                    title = track.title.ifBlank { track.recording?.title.orEmpty() },
                    // Track credits beat release credits: on a compilation the release is
                    // credited to "Various Artists", which is nobody's actual track artist.
                    artist = track.artistCredit.display()
                        ?: track.recording?.artistCredit.display()
                        ?: artistCredit.display().orEmpty(),
                    durationMs = track.length ?: track.recording?.length,
                    discNumber = medium.position,
                    isrcs = track.recording?.isrcs.orEmpty(),
                )
            }
        },
    )

    private fun MbReleaseGroup.toRow(includeArtist: Boolean = true) = ReleaseGroupRow(
        id = id,
        title = title,
        artist = if (includeArtist) artistCredit.display().orEmpty() else "",
        subtitle = listOfNotNull(
            firstReleaseDate?.take(4)?.takeIf { it.isNotBlank() },
            primaryType,
            secondaryTypes.firstOrNull(),
        ).joinToString(" \u00B7 ").ifEmpty { null },
        coverUrl = CoverArt.releaseGroup(id),
    )

    private fun MbRecording.toRow(): RecordingRow {
        val firstRelease = releases.firstOrNull()
        return RecordingRow(
            id = id,
            title = title,
            artist = artistCredit.display().orEmpty(),
            album = firstRelease?.title,
            releaseId = firstRelease?.id,
            releaseGroupId = firstRelease?.releaseGroup?.id,
            durationMs = length,
        )
    }

    private fun LibrarySnapshot.matches(row: RecordingRow) = hasTrack(
        recordingId = row.id,
        releaseTrackId = null,
        artist = row.artist,
        album = row.album,
        title = row.title,
    )

    private fun TrackRow.downloadKey(): String =
        releaseTrackId.ifEmpty { recordingId ?: title }

    private fun Exception.readableMessage(): String =
        message?.takeIf { it.isNotBlank() }?.take(200) ?: "Something went wrong"

    /** Turns a tree URI into something recognisable, e.g. `primary:Music` into `Music`. */
    private fun readableFolderName(uri: String): String = runCatching {
        SafTree.rootDocumentId(uri.toUri()).substringAfterLast(':').ifEmpty { uri }
    }.getOrDefault(uri)

    private companion object {
        // Tidal answers `slow_down` when polled too eagerly; back off by a second each time.
        const val SLOW_DOWN_STEP_MS = 1_000L
    }
}

