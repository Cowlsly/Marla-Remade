package org.schabi.newpipe.extractor.services.youtube.sabrng.media

import org.schabi.newpipe.extractor.services.youtube.sabrng.generated.SabrMediaHeader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/** One decoded SABR media (or initialization) segment, held in memory or spooled to disk. */
class SabrMediaSegment private constructor(
    private val header: SabrMediaHeader,
    private val data: ByteArray?,
    private val file: File?,
    private val length: Int,
    private val progressiveState: ProgressiveFileState?
) {
    internal constructor(header: SabrMediaHeader, data: ByteArray) :
        this(header, data, null, data.size, null)

    internal constructor(header: SabrMediaHeader, file: File, length: Int) :
        this(header, null, file, length, null)

    fun getHeader(): SabrMediaHeader = header

    /**
     * Read-only: callers must not mutate the returned array. Disk-backed segments are loaded only
     * for legacy callers; playback should use [openStream] to avoid pulling large media segments
     * back onto the Java heap.
     */
    fun getData(): ByteArray {
        data?.let { return it }
        try {
            openStream().use { input ->
                ByteArrayOutputStream(length).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    return output.toByteArray()
                }
            }
        } catch (e: IOException) {
            throw IllegalStateException("Could not read disk-backed SABR media segment", e)
        }
    }

    @Throws(IOException::class)
    fun openStream(): InputStream {
        if (data != null) {
            return ByteArrayInputStream(data)
        }
        if (file == null) {
            throw IOException("SABR media segment has no backing data")
        }
        if (progressiveState != null) {
            progressiveState.throwIfFailed()
            try {
                return ProgressiveFileInputStream(file, progressiveState)
            } catch (e: IOException) {
                progressiveState.throwIfFailed()
                throw e
            }
        }
        return FileInputStream(file)
    }

    fun isDiskBacked(): Boolean = file != null

    fun isComplete(): Boolean = progressiveState == null || progressiveState.isComplete()

    fun hasFailed(): Boolean = progressiveState != null && progressiveState.hasFailed()

    internal fun onBytesWritten(count: Int) {
        progressiveState?.onBytesWritten(count)
    }

    internal fun completeProgressive() {
        progressiveState?.complete()
    }

    fun failProgressive(failure: IOException) {
        progressiveState?.fail(failure)
    }

    fun delete() {
        failProgressive(IOException("SABR media segment was discarded"))
        if (file != null && !file.delete() && file.exists()) {
            file.deleteOnExit()
        }
    }

    fun getLength(): Int = length

    internal fun getFile(): File? = file

    /** Internal bridge for media assembly implementations living in the media subpackage. */
    object InternalAccess {
        @JvmStatic
        fun fromBytes(header: SabrMediaHeader, data: ByteArray): SabrMediaSegment =
            SabrMediaSegment(header, data)

        @JvmStatic
        fun fromFile(header: SabrMediaHeader, file: File, length: Int): SabrMediaSegment =
            SabrMediaSegment(header, file, length)

        @JvmStatic
        fun progressive(header: SabrMediaHeader, file: File, length: Int): SabrMediaSegment =
            SabrMediaSegment(header, null, file, length, ProgressiveFileState(length))

        @JvmStatic
        fun onBytesWritten(segment: SabrMediaSegment, count: Int) = segment.onBytesWritten(count)

        @JvmStatic
        fun completeProgressive(segment: SabrMediaSegment) = segment.completeProgressive()

        @JvmStatic
        fun failProgressive(segment: SabrMediaSegment, failure: IOException) =
            segment.failProgressive(failure)
    }

    internal class ProgressiveFileState(private val expectedLength: Int) {
        private var bytesWritten = 0
        private var complete = false
        private var failure: IOException? = null

        @Synchronized
        fun onBytesWritten(count: Int) {
            if (count <= 0 || complete || failure != null) {
                return
            }
            bytesWritten += count
            (this as Object).notifyAll()
        }

        @Synchronized
        fun complete() {
            if (failure == null) {
                complete = true
            }
            (this as Object).notifyAll()
        }

        @Synchronized
        fun fail(exception: IOException) {
            if (!complete && failure == null) {
                failure = exception
            }
            (this as Object).notifyAll()
        }

        @Synchronized
        fun isComplete(): Boolean = complete

        @Synchronized
        fun hasFailed(): Boolean = failure != null

        @Synchronized
        @Throws(IOException::class)
        fun awaitAvailable(position: Long, reader: ProgressiveFileInputStream): Int {
            var readable = readableBytes(position)
            while (readable <= 0 && !complete && failure == null && !reader.closed) {
                try {
                    (this as Object).wait()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    val interrupted = InterruptedIOException("Interrupted waiting for SABR media bytes")
                    interrupted.initCause(e)
                    throw interrupted
                }
                readable = readableBytes(position)
            }
            if (reader.closed) {
                throw InterruptedIOException("SABR media stream was closed")
            }
            failure?.let { throw it }
            return if (complete) Math.max(0, (bytesWritten - position).toInt()) else readable
        }

        @Synchronized
        @Throws(IOException::class)
        fun available(position: Long): Int {
            failure?.let { throw it }
            return if (complete) {
                Math.max(0, (bytesWritten - position).toInt())
            } else {
                readableBytes(position)
            }
        }

        private fun readableBytes(position: Long): Int {
            val available = Math.max(0, (bytesWritten - position).toInt())
            if (!complete && bytesWritten >= expectedLength && position + available >= expectedLength) {
                // Keep one byte unavailable until MEDIA_END validates the segment. Media3 knows the
                // declared DataSource length and may never issue a separate EOF read.
                return Math.max(0, available - 1)
            }
            return available
        }

        @Synchronized
        fun signalReaders() {
            (this as Object).notifyAll()
        }

        @Synchronized
        @Throws(IOException::class)
        fun throwIfFailed() {
            failure?.let { throw it }
        }
    }

    internal class ProgressiveFileInputStream @Throws(IOException::class) constructor(
        file: File,
        private val state: ProgressiveFileState
    ) : InputStream() {
        private val input: RandomAccessFile = RandomAccessFile(file, "r")
        private var position: Long = 0

        @Volatile
        var closed: Boolean = false
            private set

        @Throws(IOException::class)
        override fun read(): Int {
            val one = ByteArray(1)
            val read = read(one, 0, 1)
            return if (read < 0) -1 else one[0].toInt() and 0xff
        }

        @Throws(IOException::class)
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            if (closed) {
                throw IOException("SABR media stream is closed")
            }
            if (count == 0) {
                return 0
            }
            while (true) {
                val available = state.awaitAvailable(position, this)
                if (available <= 0) {
                    return -1
                }
                input.seek(position)
                val read = input.read(bytes, offset, Math.min(count, available))
                if (read > 0) {
                    position += read
                    return read
                }
            }
        }

        @Throws(IOException::class)
        override fun skip(count: Long): Long {
            if (count <= 0) {
                return 0
            }
            val available = state.awaitAvailable(position, this)
            if (available <= 0) {
                return 0
            }
            val skipped = Math.min(count, available.toLong())
            position += skipped
            input.seek(position)
            return skipped
        }

        @Throws(IOException::class)
        override fun available(): Int {
            if (closed) {
                return 0
            }
            return state.available(position)
        }

        @Throws(IOException::class)
        override fun close() {
            if (!closed) {
                closed = true
                state.signalReaders()
                input.close()
            }
        }
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 8192
    }
}
