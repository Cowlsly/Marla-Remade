package com.vayunmathur.library.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Repackages a WebM/Opus stream as an Ogg/Opus file without re-encoding.
 *
 * YouTube serves its highest-quality audio as Opus inside a WebM container, but a `.opus`
 * file is Opus inside Ogg. The audio itself is identical, so this only rewrites the
 * container: the Opus packets are demuxed with [MediaExtractor] and stream-copied into an
 * Ogg/Opus file with [MediaMuxer]. There is no transcode, so no quality is lost.
 *
 * This mirrors the framework-only remux the YouPipe app uses for SABR downloads, chosen so
 * the repo can stay ffmpeg-free and F-Droid publishable.
 */
object OpusRemuxer {

    private const val TAG = "OpusRemuxer"
    private const val INITIAL_BUFFER_SIZE = 1 * 1024 * 1024

    /**
     * Returns the Ogg/Opus bytes, or null if the input has no readable Opus track or the
     * platform muxer refuses it. Callers keep the original bytes on null so a download is
     * never lost to a remux failure.
     */
    fun remux(context: Context, webmOpus: ByteArray): ByteArray? {
        val input = File.createTempFile("mb-remux-in", ".webm", context.cacheDir)
        val output = File.createTempFile("mb-remux-out", ".ogg", context.cacheDir)
        // MediaMuxer insists on creating its own output file.
        output.delete()
        return try {
            input.writeBytes(webmOpus)
            if (muxToOgg(input, output)) {
                output.readBytes().takeIf { it.isNotEmpty() }
                    .also { Log.i(TAG, "remux ok: in=${webmOpus.size} out=${it?.size ?: 0}") }
            } else {
                Log.w(TAG, "remux failed: muxToOgg returned false (in=${webmOpus.size})")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "remux threw: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        } finally {
            input.delete()
            output.delete()
        }
    }

    private fun muxToOgg(input: File, output: File): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(input.absolutePath) }

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                Log.i(TAG, "remux track $i mime=$mime")
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex == -1 || format == null) {
                Log.w(TAG, "remux: no audio track found in ${extractor.trackCount} tracks")
                return false
            }

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            val muxerTrack = muxer.addTrack(format)
            muxer.start()
            extractor.selectTrack(trackIndex)

            var buffer = ByteBuffer.allocate(INITIAL_BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                buffer.clear()
                var sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                if (sampleSize > buffer.capacity()) {
                    buffer = ByteBuffer.allocate(sampleSize)
                    buffer.clear()
                    sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                }
                info.set(0, sampleSize, extractor.sampleTime, bufferFlagsFor(extractor.sampleFlags))
                muxer.writeSampleData(muxerTrack, buffer, info)
                extractor.advance()
                samples++
            }
            Log.i(TAG, "remux: copied $samples samples")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "remux muxToOgg threw: ${e.javaClass.simpleName}: ${e.message}", e)
            return false
        } finally {
            runCatching { extractor?.release() }
            muxer?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
        }
    }

    // MediaExtractor.SAMPLE_FLAG_* and MediaCodec.BUFFER_FLAG_* are distinct constant spaces.
    private fun bufferFlagsFor(sampleFlags: Int): Int =
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
}
