package com.vayunmathur.youpipe.util
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vayunmathur.youpipe.util.sabr.LocalDomPoTokenProvider
import com.vayunmathur.youpipe.util.sabr.SabrNgDashMediaSource
import com.vayunmathur.youpipe.util.sabr.SabrNgSessionStore
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isAndroidStreamingUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isIosStreamingUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isVisionOsStreamingUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getAndroidUserAgent
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getIosUserAgent
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getVisionOsUserAgent

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    companion object {
        const val EXTRA_AUDIO_URI = "extra_audio_uri"
        const val EXTRA_AUDIO_TRACK_ID = "extra_audio_track_id"
        const val EXTRA_AUDIO_ITAG = "extra_audio_itag"
    }

    override fun onCreate() {
        super.onCreate()

        val defaultUserAgent = "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"

        // Prefer HttpURLConnection-backed default datasource; SABR path uses library:network.
        // Inject per-request User-Agent via ResolvingDataSource so range/redirect code still inherits it.
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent(defaultUserAgent)
            setConnectTimeoutMs(30_000)
            setReadTimeoutMs(30_000)
            setAllowCrossProtocolRedirects(true)
        }

        val resolvingFactory = androidx.media3.datasource.ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            val url = dataSpec.uri.toString()
            val userAgent = when {
                isAndroidStreamingUrl(url) -> getAndroidUserAgent(null)
                isIosStreamingUrl(url) -> getIosUserAgent(null)
                isVisionOsStreamingUrl(url) -> getVisionOsUserAgent(null)
                else -> defaultUserAgent
            }
            val headers = buildMap {
                put("User-Agent", userAgent)
                dataSpec.httpRequestHeaders.forEach { (k, v) -> if (!k.equals("User-Agent", ignoreCase = true)) put(k, v) }
            }
            dataSpec.withRequestHeaders(headers)
        }

        val dataSourceFactory = DefaultDataSource.Factory(this, resolvingFactory)

        // 1. Use delegation instead of inheritance
        val defaultMediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val customMediaSourceFactory = object : MediaSource.Factory {
            @Suppress("DEPRECATION")
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val subtitleSources = mediaItem.localConfiguration?.subtitleConfigurations
                    ?.map { cfg ->
                        SingleSampleMediaSource.Factory(dataSourceFactory)
                            .setTreatLoadErrorsAsEndOfStream(true)
                            .createMediaSource(cfg, C.TIME_UNSET)
                    } ?: emptyList()

                // SABR: the sabr:// video URI carries both audio and video via one MediaSource.
                val vUri = mediaItem.localConfiguration?.uri
                if (vUri != null && vUri.scheme == "sabr") {
                    val videoId = vUri.host
                        ?: throw RuntimeException("SABR media item is missing a video id: $vUri")
                    val vitag = vUri.getQueryParameter("v")?.toIntOrNull() ?: 0
                    val extras = mediaItem.mediaMetadata.extras
                    val rawAudioUri = mediaItem.localConfiguration?.tag as? String
                        ?: extras?.getString(EXTRA_AUDIO_URI)
                    var audioItag = extras?.getInt(EXTRA_AUDIO_ITAG, 0) ?: 0
                    var audioTrackIdOverride: String? = extras?.getString(EXTRA_AUDIO_TRACK_ID)
                    if (audioItag == 0 && rawAudioUri != null) {
                        try {
                            val aUri = rawAudioUri.toUri()
                            if (aUri.scheme == "sabr") {
                                audioItag = aUri.getQueryParameter("a")?.toIntOrNull() ?: 0
                            }
                        } catch (_: Exception) {}
                    }
                    val info = SabrNgSessionStore.getExtractorInfo(videoId)
                        ?: throw RuntimeException("No SABR info available for $videoId")
                    val provider = LocalDomPoTokenProvider.shared(applicationContext)
                    val localization = Localization("en", "US")
                    // Defer PO-token minting + init-segment fetch to the SABR source's background
                    // open path. Doing this on the main thread (via createSourceSpec here) blocked
                    // setMediaItems for several seconds and triggered an ANR. The token is still
                    // minted lazily at play time (first source open), preserving the known-working
                    // ordering that avoids the SABR bootstrap 403 regression.
                    val sabrSource = run {
                        val vAudioItag = audioItag
                        val vAudioTrackId = audioTrackIdOverride
                        val tokenMinter: (Boolean) -> ByteArray? = { force ->
                            provider.getPoTokenBytes(videoId, info.getVisitorData(), force)
                        }
                        val specSupplier: () -> com.vayunmathur.youpipe.util.sabr.SabrNgSourceSpec = {
                            val poToken = tokenMinter(false)
                            SabrNgSessionStore.createSourceSpec(
                                videoId, vitag, vAudioItag, vAudioTrackId, info, poToken,
                                localization, tokenMinter
                            )
                        }
                        SabrNgDashMediaSource(
                            this@PlaybackService, mediaItem, videoId, specSupplier,
                            tokenMinter
                        )
                    }
                    return if (subtitleSources.isNotEmpty()) {
                        MergingMediaSource(sabrSource, *subtitleSources.toTypedArray())
                    } else {
                        sabrSource
                    }
                }

                val audioUriString = mediaItem.localConfiguration?.tag as? String
                    ?: mediaItem.mediaMetadata.extras?.getString(EXTRA_AUDIO_URI)

                return if (audioUriString != null) {
                    val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(mediaItem)
                    val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(audioUriString))

                    MergingMediaSource(videoSource, audioSource, *subtitleSources.toTypedArray())
                } else {
                    val videoSource = defaultMediaSourceFactory.createMediaSource(mediaItem)
                    if (subtitleSources.isNotEmpty()) {
                        MergingMediaSource(videoSource, *subtitleSources.toTypedArray())
                    } else {
                        videoSource
                    }
                }
            }

            override fun setDrmSessionManagerProvider(drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider) =
                apply { defaultMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider) }

            override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy) =
                apply { defaultMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy) }

            override fun getSupportedTypes(): IntArray = defaultMediaSourceFactory.supportedTypes
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        try {
            android.util.Log.d("YouPipeSubs", "Attempting to enable legacy text decoding")
        } catch (_: Exception) {}

        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            @OptIn(ExperimentalApi::class)
            override fun buildTextRenderers(
                context: android.content.Context,
                output: androidx.media3.exoplayer.text.TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                out.filterIsInstance<androidx.media3.exoplayer.text.TextRenderer>().forEach { textRenderer ->
                    try {
                        textRenderer.experimentalSetLegacyDecodingEnabled(true)
                    } catch (_: Exception) {}
                }
                if (out.none { it.trackType == androidx.media3.common.C.TRACK_TYPE_TEXT }) {
                    val tr = androidx.media3.exoplayer.text.TextRenderer(output, outputLooper)
                    try {
                        tr.experimentalSetLegacyDecodingEnabled(true)
                    } catch (_: Exception) {}
                    out.add(tr)
                }
            }
        }.setEnableDecoderFallback(true)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(customMediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val callback = object : MediaSession.Callback {
            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                val updatedItems = mediaItems.map { item ->
                    val extras = item.mediaMetadata.extras
                    val audioUri = extras?.getString(EXTRA_AUDIO_URI)
                    item.buildUpon()
                        .setTag(audioUri)
                        .build()
                }.toMutableList()
                return Futures.immediateFuture(updatedItems)
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(callback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.stop()
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player?.isPlaying == true) {
            try {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
            } catch (_: Exception) {}
            return
        }
        player?.let {
            it.pause()
            it.stop()
        }
        stopSelf()
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (session.player.playbackState == Player.STATE_IDLE ||
            session.player.playbackState == Player.STATE_ENDED) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }
}
