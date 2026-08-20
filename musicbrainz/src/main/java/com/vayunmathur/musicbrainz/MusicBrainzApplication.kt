package com.vayunmathur.musicbrainz

import android.app.Application
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.musicbrainz.data.download.MbDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class MusicBrainzApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // MUSICBRAINZ rather than STANDARD: musicbrainz.org and lrclib.net are ISRG,
        // coverartarchive.org redirects to archive.org / ia*.us.archive.org on GoDaddy certs,
        // and Tidal's Fastly-fronted audio CDN is GlobalSign - the last of which is the one
        // root STANDARD does not carry.
        NetworkClient.init(this, TrustBundle.MUSICBRAINZ)
        NewPipe.init(MbDownloader(), Localization.fromLocale(Locale.getDefault()))
    }
}
