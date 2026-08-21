package com.vayunmathur.cast.platform.mirror

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream

private const val TAG = "PcmAudioEncoder"

/**
 * Reads PCM another app wrote into a pipe and encodes it as Opus.
 *
 * The SDK's half of [AudioStream]. A pipe rather than IPC messages because 48 kHz stereo 16-bit is
 * ~192 KB/s, which as `Messenger` messages would be fifty Binder transactions a second competing
 * with everything else on the binder thread.
 *
 * **Never blocks.** `read` on a pipe with nothing in it parks the calling thread, and
 * [MirrorEngine.stop] cancels *and joins* its loops before releasing anything they touch - a thread
 * parked in a blocking read would not answer the cancellation and the join would hang. So this reads
 * only what `available()` says is there and returns empty otherwise, leaving the loop to poll.
 *
 * EOF is deliberately not treated as the end of the session: the binding is what ends a session, and
 * a client that has closed its pipe but not unbound is one that has stopped sending audio rather than
 * stopped casting. Whatever partial frame is left over is held until enough arrives to complete it,
 * because Opus wants whole 20 ms frames and half of one encodes as a click.
 */
class PcmAudioEncoder(private val readEnd: ParcelFileDescriptor) : AudioStream {

    private var input: FileInputStream? = null
    private val opus = OpusEncoder()

    /** One 20 ms frame under construction; the app's write sizes are its own business. */
    private val frame = ByteArray(OpusEncoder.FRAME_BYTES)
    private var filled = 0

    override fun start(): Boolean {
        if (!opus.start()) return false
        return try {
            input = FileInputStream(readEnd.fileDescriptor)
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not open the PCM pipe", e)
            release()
            false
        }
    }

    override fun pump(): List<EncodedChunk> {
        val stream = input ?: return emptyList()
        val available = try {
            stream.available()
        } catch (e: Exception) {
            Log.w(TAG, "the PCM pipe went away", e)
            return emptyList()
        }
        if (available <= 0) return emptyList()
        val read = try {
            stream.read(frame, filled, minOf(frame.size - filled, available))
        } catch (e: Exception) {
            Log.w(TAG, "PCM read failed", e)
            return emptyList()
        }
        if (read <= 0) return emptyList()
        filled += read
        if (filled < frame.size) return emptyList()
        filled = 0
        return opus.encode(frame, frame.size)
    }

    override fun release() {
        runCatching { input?.close() }
        runCatching { readEnd.close() }
        input = null
        opus.release()
    }
}
