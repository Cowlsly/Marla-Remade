package com.vayunmathur.musicbrainz.platform

import androidx.annotation.StringRes
import com.vayunmathur.musicbrainz.R

/**
 * Where downloaded audio comes from.
 *
 * MusicBrainz hosts no audio, so a download always needs an outside source. YouTube needs
 * no account but serves lossy re-encodes matched on a search query; Tidal needs a
 * subscription but is a catalogue, so a track is matched on its ISRC and the stream it
 * serves is lossless before the app re-encodes it.
 */
enum class DownloadSource(@StringRes val labelRes: Int) {
    YouTube(R.string.download_source_youtube),
    Tidal(R.string.download_source_tidal),
}
