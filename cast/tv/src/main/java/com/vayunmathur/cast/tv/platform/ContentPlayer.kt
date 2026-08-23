package com.vayunmathur.cast.tv.platform

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.EphemeralTls
import com.vayunmathur.cast.protocol.MediaProxy
import com.vayunmathur.cast.protocol.PlayMedia
import com.vayunmathur.cast.protocol.ProtocolBase64
import javax.net.ssl.HttpsURLConnection

private const val TAG = "ContentPlayer"

/**
 * Plays app content the phone is serving, rather than decoding pixels it encoded.
 *
 * This is the other half of the change that removes the encoder from the phone. The TV fetches byte
 * ranges of the original media over HTTPS and hands them to its own decoder, so it owns the clock,
 * the buffer and the seeking - and every pathology of the RTP path stops applying rather than being
 * fixed. A pause is a pause. A seek is a byte offset. There is no picture-loss indicator because
 * there are no lost pictures to indicate.
 *
 * **Trust is the session's, not a PKI's.** The certificate fingerprint arrived over the control
 * channel, which the pairing already authenticated, so the player's data source trusts exactly that
 * one certificate. Nothing here is told to skip verification - the point of pinning is that it
 * verifies something stronger than a name.
 */
@OptIn(UnstableApi::class)
class ContentPlayer(
    private val context: Context,
    private val session: ContentSession,
) {

    private var player: ExoPlayer? = null

    /** Where playback is, for the phone. Null until the player has produced anything. */
    val position: Long get() = player?.currentPosition ?: 0L
    val duration: Long get() = player?.duration?.takeIf { it > 0 } ?: 0L
    val isPlaying: Boolean get() = player?.isPlaying == true
    val isBuffering: Boolean get() = player?.playbackState == Player.STATE_BUFFERING

    /**
     * Builds the player, or returns false if the fingerprint was unusable.
     *
     * [onError] is reported rather than swallowed: a failed load on this path used to be invisible,
     * and an expired resource that produces no error and no picture is the hardest kind of fault to
     * attribute.
     */
    fun start(onError: (String) -> Unit): Boolean {
        val fingerprint = ProtocolBase64.decode(session.certificateFingerprint)
        if (fingerprint == null || fingerprint.size != FINGERPRINT_BYTES) {
            Log.w(TAG, "the phone sent an unusable certificate fingerprint")
            return false
        }

        // Process-wide because `DefaultHttpDataSource` is `HttpURLConnection`-backed and takes its
        // socket factory from the default. Acceptable here and nowhere else: this app makes no other
        // HTTPS request, so there is nothing else whose trust could be narrowed by accident.
        HttpsURLConnection.setDefaultSSLSocketFactory(EphemeralTls.client(fingerprint).socketFactory)

        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            // The proxy answers from a file descriptor the phone's app supplied, and can be slow to
            // get one. A redirect is never legitimate here.
            .setAllowCrossProtocolRedirects(false)

        val built = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        built.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Deliberately loud. The old path reported load errors as end-of-stream, so an
                // expired URL produced silence and no explanation at either end.
                Log.w(TAG, "playback failed: ${error.errorCodeName}", error)
                onError(error.errorCodeName)
            }
        })
        built.playWhenReady = true
        player = built
        Log.i(
            TAG,
            "content player up for ${session.host}:${session.port}, " +
                if (session.video) "audio and video" else "audio only",
        )
        return true
    }

    /** Points the player at a resource on the proxy and starts it. */
    fun play(media: PlayMedia) {
        val active = player ?: return
        val url = MediaProxy.url(session.host, session.port, session.token, media.resourceId)
        val item = MediaItem.Builder()
            .setUri(url)
            // Stated rather than sniffed: the phone knows what it is serving, and sniffing costs a
            // round trip before anything can start.
            .setMimeType(media.mimeType.ifBlank { MimeTypes.BASE_TYPE_AUDIO })
            .build()
        Log.i(TAG, "playing ${media.resourceId} (${media.mimeType})")
        active.setMediaItem(item)
        active.prepare()
        active.play()
    }

    /**
     * Hands the player a surface, or takes it away.
     *
     * Null is the audio-only case and the case where the TV's surface has gone - handing the player
     * a surface that has been destroyed is what makes the *next* session fail rather than this one.
     */
    fun setSurface(surface: Surface?) {
        player?.setVideoSurface(surface)
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun seekTo(positionMs: Long) {
        // A byte offset on this path, not a key-frame renegotiation. This is the line that used to
        // be a round trip to the phone and a wait for a fresh key frame.
        player?.seekTo(positionMs)
    }

    fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    fun release() {
        runCatching { player?.release() }
        player = null
    }

    private companion object {
        const val FINGERPRINT_BYTES = 32

        /** The phone is on the same LAN, so a slow connect means something is wrong rather than far. */
        const val CONNECT_TIMEOUT_MS = 8_000

        /**
         * Longer than the connect timeout, because the proxy may be waiting on the app for a
         * descriptor - but bounded, so a phone that has stopped answering surfaces as a load error
         * rather than as a picture that never arrives.
         */
        const val READ_TIMEOUT_MS = 15_000
    }
}
