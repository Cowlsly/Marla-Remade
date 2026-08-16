package org.schabi.newpipe.extractor.services.youtube.sabrng

/** Immutable description of one SABR transaction. */
class YoutubeSabrRequest private constructor(
    tracks: Collection<Track>,
    playbackState: PlaybackState,
    private val selectedTracks: Boolean
) {
    private val tracks: List<Track>
    private val playbackState: PlaybackState

    init {
        require(tracks.isNotEmpty()) { "SABR request must contain at least one track" }
        val copy = ArrayList<Track>(tracks.size)
        var audioFormat: YoutubeSabrInfo.Format? = null
        var videoFormat: YoutubeSabrInfo.Format? = null
        for (track in tracks) {
            when {
                track.getFormat().isAudio() -> {
                    require(audioFormat == null) {
                        "SABR request must not contain multiple audio tracks"
                    }
                    audioFormat = track.getFormat()
                }
                track.getFormat().isVideo() -> {
                    require(videoFormat == null) {
                        "SABR request must not contain multiple video tracks"
                    }
                    videoFormat = track.getFormat()
                }
                else -> throw IllegalArgumentException(
                    "SABR request format has no track type: itag=" + track.getFormat().getItag()
                )
            }
            copy.add(track)
        }
        require(
            !(audioFormat != null && videoFormat != null &&
                audioFormat.getItag() == videoFormat.getItag())
        ) { "SABR audio/video formats must be distinct" }
        this.tracks = java.util.Collections.unmodifiableList(copy)
        this.playbackState = playbackState
    }

    internal fun getTracks(): List<Track> = tracks
    internal fun getPlaybackState(): PlaybackState = playbackState
    internal fun hasSelectedTracks(): Boolean = selectedTracks

    internal fun getAudioTrack(): Track? = tracks.firstOrNull { it.getFormat().isAudio() }
    internal fun getVideoTrack(): Track? = tracks.firstOrNull { it.getFormat().isVideo() }

    class Track private constructor(
        private val format: YoutubeSabrInfo.Format,
        private val timeline: YoutubeSabrFormatTimeline?,
        private val bufferedThrough: Int
    ) {
        init {
            require(bufferedThrough >= 0) {
                "SABR buffered-through sequence must not be negative"
            }
        }

        internal fun getFormat(): YoutubeSabrInfo.Format = format
        internal fun getTimeline(): YoutubeSabrFormatTimeline? = timeline
        internal fun getBufferedThrough(): Int = bufferedThrough

        companion object {
            @JvmStatic
            fun of(
                format: YoutubeSabrInfo.Format,
                timeline: YoutubeSabrFormatTimeline?,
                bufferedThrough: Int
            ): Track = Track(format, timeline, bufferedThrough)
        }
    }

    internal class PlaybackState(private val playerTimeMs: Long, private val playbackRate: Float) {
        fun getPlayerTimeMs(): Long = playerTimeMs
        fun getPlaybackRate(): Float = playbackRate
    }

    companion object {
        /**
         * Prepares format timelines around a playback position without declaring selected tracks.
         * Media returned alongside initialization segments may be retained by the caller.
         */
        @JvmStatic
        fun preparation(
            playerTimeMs: Long,
            preferredFormats: Collection<YoutubeSabrInfo.Format>
        ): YoutubeSabrRequest {
            require(playerTimeMs >= 0) { "SABR player time must not be negative" }
            val tracks = ArrayList<Track>(preferredFormats.size)
            for (format in preferredFormats) {
                tracks.add(Track.of(format, null, 0))
            }
            return YoutubeSabrRequest(tracks, PlaybackState(playerTimeMs, 1.0f), false)
        }

        /** Requests media for the active tracks in their preferred response order. */
        @JvmStatic
        fun playback(
            playerTimeMs: Long,
            playbackRate: Float,
            tracks: Collection<Track>
        ): YoutubeSabrRequest {
            require(playerTimeMs >= 0) { "SABR player time must not be negative" }
            require(playbackRate > 0) { "SABR playback rate must be positive" }
            return YoutubeSabrRequest(tracks, PlaybackState(playerTimeMs, playbackRate), true)
        }
    }
}
