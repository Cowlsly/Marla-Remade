package com.vayunmathur.youpipe.util.sabr

import android.content.Context
import android.util.Log
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrFormatTimeline
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Session-based ([SabrNgSession]) SABR downloader: drives `requestOnce` to fetch the selected
 * audio+video formats to two temporary files (init segment followed by all media segments) and
 * muxes them into a single mp4 via [SabrFfmpegMuxer]. Replaces the old `SabrDownloadHelper`, which
 * drove the legacy SABR session API.
 */
internal object SabrNgDownloadHelper {
    private const val TAG = "SabrNgDownloadHelper"
    private const val SEGMENT_TIMEOUT_MS = 30_000L
    private val DOWNLOAD_LOCALIZATION = Localization("en", "US")

    @Throws(IOException::class)
    fun download(
        context: Context,
        videoId: String,
        videoItag: Int,
        audioItag: Int,
        audioTrackId: String?,
        info: YoutubeSabrInfo,
        workDir: File,
        outputFile: File,
        onProgress: (Double) -> Unit,
    ) {
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw IOException("Could not create SABR download directory: $workDir")
        }
        val poToken = LocalDomPoTokenProvider.shared(context.applicationContext)
            .getPoTokenBytes(videoId, info.getVisitorData())
        val spec = SabrNgSessionStore.createSourceSpec(
            videoId, videoItag, audioItag, audioTrackId, info, poToken, DOWNLOAD_LOCALIZATION
        )
        val audioFile = File(workDir, "sabr-audio-$videoId-${spec.audioFormat.getItag()}.media")
        val videoFile = File(workDir, "sabr-video-$videoId-${spec.videoFormat.getItag()}.media")
        val spoolDir = File(workDir, "spool").apply { mkdirs() }
        val session = SabrNgSession(spec, spoolDir, onAttestationFailure = null)

        val totalSegments =
            (spec.audioTimeline.getEndSequence() + spec.videoTimeline.getEndSequence())
                .coerceAtLeast(1)
        var written = 0
        val progress = { onProgress((written.toDouble() / totalSegments).coerceIn(0.0, 1.0)) }
        try {
            session.start()
            writeTrack(session, spec.audioFormat.getItag(), spec.audioTimeline, audioFile) {
                written++
                progress()
            }
            writeTrack(session, spec.videoFormat.getItag(), spec.videoTimeline, videoFile) {
                written++
                progress()
            }
            SabrFfmpegMuxer.mux(videoFile, audioFile, outputFile)
            onProgress(1.0)
        } catch (e: Exception) {
            Log.e(TAG, "SABR download failed for $videoId", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            if (e is IOException) throw e
            throw IOException("SABR download failed: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            session.stop()
            audioFile.delete()
            videoFile.delete()
            spoolDir.deleteRecursively()
        }
    }

    @Throws(IOException::class)
    private fun writeTrack(
        session: SabrNgSession,
        itag: Int,
        timeline: YoutubeSabrFormatTimeline,
        file: File,
        onSegment: () -> Unit,
    ) {
        FileOutputStream(file).use { out ->
            session.getInitialization(itag)?.let { out.write(it) }
            for (sequence in 1..timeline.getEndSequence()) {
                val segment = session.getMediaSegment(itag, sequence, SEGMENT_TIMEOUT_MS)
                    ?: throw IOException("Missing SABR segment itag=$itag seq=$sequence")
                segment.openStream().use { it.copyTo(out) }
                onSegment()
            }
        }
    }
}
