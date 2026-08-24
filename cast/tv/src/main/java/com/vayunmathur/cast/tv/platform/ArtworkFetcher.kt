package com.vayunmathur.cast.tv.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.EphemeralTls
import com.vayunmathur.cast.protocol.MediaProxy
import com.vayunmathur.cast.protocol.ProtocolBase64
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val TAG = "ArtworkFetcher"

/**
 * Fetches a cover from the phone's media proxy.
 *
 * Artwork travels as a resource rather than as bytes on the control channel, which reuses the whole
 * path the player already uses - `MediaProxyServer`'s own KDoc names this case: "a worst case is
 * video, audio, a caption track and artwork at once". The alternative would put a few hundred
 * kilobytes of Base64 through AES-GCM framing per track, against a 1 MiB frame limit, to avoid one
 * more HTTP request down a connection that is making thousands.
 *
 * **The pin is set on the connection, not inherited.** [ContentPlayer] installs one process-wide with
 * `HttpsURLConnection.setDefaultSSLSocketFactory`, because `DefaultHttpDataSource` takes its factory
 * from the static default - and relying on that here would be a bug that only appears sometimes: the
 * first snapshot can arrive before the player has been built, and this fetch would then validate a
 * self-signed ephemeral certificate against the platform's authorities and fail. So the factory is
 * built here and assigned to the connection. The session is needed for the host, the port and the
 * token anyway, so the fingerprint is already in hand.
 *
 * Every failure is null and a warning. A cover is enrichment: a session must not end because a JPEG
 * did not arrive, and the screen has a deliberate placeholder for exactly this.
 */
class ArtworkFetcher(private val session: ContentSession) {

    private val socketFactory = ProtocolBase64.decode(session.certificateFingerprint)
        ?.takeIf { it.size == FINGERPRINT_BYTES }
        ?.let { runCatching { EphemeralTls.client(it).socketFactory }.getOrNull() }

    /** Blocking. Call it off the main thread. */
    fun fetch(resourceId: String): Bitmap? {
        val factory = socketFactory ?: run {
            Log.w(TAG, "no usable certificate fingerprint, so no artwork will be fetched")
            return null
        }
        val url = MediaProxy.url(session.host, session.port, session.token, resourceId)
        return runCatching {
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.sslSocketFactory = factory
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            try {
                if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
                    Log.w(TAG, "the proxy answered ${connection.responseCode} for '$resourceId'")
                    return@runCatching null
                }
                // Read whole, then decode: the phone bounds a cover to 1280px on its longest edge,
                // so there is nothing here worth a pre-pass decode of the bounds to guard against.
                val bytes = connection.inputStream.use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "could not fetch artwork '$resourceId'", it) }.getOrNull()
    }

    private companion object {
        const val FINGERPRINT_BYTES = 32

        /** The phone is on the same LAN, and a cover is not worth waiting on as long as media is. */
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
    }
}
