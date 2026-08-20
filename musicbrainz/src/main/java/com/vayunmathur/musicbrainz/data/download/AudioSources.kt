package com.vayunmathur.musicbrainz.data.download

import android.content.Context
import com.vayunmathur.musicbrainz.platform.DownloadSource

/**
 * The download-source fallback policy, in one place.
 *
 * Tidal is a catalogue lookup, so it can simply not carry a recording, and a subscription
 * can lapse. It is therefore always backed by YouTube: a download quietly falling through
 * to a lossy source is better than one disappearing.
 */
object AudioSources {

    /** The sources to try, in order, for a user who prefers [preferred]. */
    fun ordered(context: Context, preferred: DownloadSource): List<AudioSource> =
        when (preferred) {
            DownloadSource.Tidal -> listOf(TidalAudioSource(context), YouTubeAudioSource)
            DownloadSource.YouTube -> listOf(YouTubeAudioSource)
        }
}
