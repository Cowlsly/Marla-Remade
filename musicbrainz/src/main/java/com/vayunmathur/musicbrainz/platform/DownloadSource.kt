package com.vayunmathur.musicbrainz.platform

import androidx.annotation.StringRes
import com.vayunmathur.musicbrainz.R

/**
 * Where downloaded audio comes from.
 *
 * MusicBrainz hosts no audio, so a download always needs an outside source. YouTube needs
 * no account but serves lossy re-encodes matched on a search query; Tidal needs a
 * subscription but is a catalogue, so a track is matched on its ISRC and arrives lossless.
 */
enum class DownloadSource(@StringRes val labelRes: Int) {
    YouTube(R.string.download_source_youtube),
    Tidal(R.string.download_source_tidal),
}

/**
 * The best stream to ask Tidal for.
 *
 * [apiValue] is Tidal's own `audioquality` parameter. The request is only ever a ceiling:
 * Tidal downgrades server-side when the subscription or the track cannot serve it, and the
 * response says which quality it actually returned.
 */
enum class TidalQuality(@StringRes val labelRes: Int, val apiValue: String) {
    Low(R.string.tidal_quality_low, "LOW"),
    Normal(R.string.tidal_quality_normal, "HIGH"),
    High(R.string.tidal_quality_high, "LOSSLESS"),
    Max(R.string.tidal_quality_max, "HI_RES_LOSSLESS"),
}
