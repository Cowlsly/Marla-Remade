package com.vayunmathur.library.media

import android.media.MediaDataSource

/**
 * Lets [android.media.MediaExtractor] read the downloaded bytes straight out of RAM.
 *
 * The download is already buffered in memory so it can be tagged before it is filed away,
 * and writing it back out to a temp file first would double the peak storage a hi-res track
 * needs - well over 200 MB for a long 24/192 recording - and add a cleanup path that has to
 * survive every failure.
 */
internal class ByteArrayMediaDataSource(private val bytes: ByteArray) : MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        ByteArrayReads.readAt(bytes, position, buffer, offset, size)

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() = Unit
}

/**
 * The read logic on its own, so it can be tested without the framework class.
 *
 * The contract has sharp edges - end of stream is -1 rather than 0, and a read that starts
 * inside the buffer but runs past the end has to be truncated rather than refused - and
 * getting either wrong makes the extractor report a corrupt file.
 */
internal object ByteArrayReads {

    /** Returns the number of bytes copied, or -1 once [position] is at or past the end. */
    fun readAt(source: ByteArray, position: Long, destination: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= source.size) return -1
        if (size <= 0) return 0
        val available = (source.size - position).coerceAtMost(size.toLong()).toInt()
        System.arraycopy(source, position.toInt(), destination, offset, available)
        return available
    }
}
