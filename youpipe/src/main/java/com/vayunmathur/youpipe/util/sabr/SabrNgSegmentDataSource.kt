package com.vayunmathur.youpipe.util.sabr

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InputStream

/**
 * media3 [DataSource] that serves SABR segments from a [SabrNgSession]. URIs follow
 * `sabrseg://<itag>/init` for the initialization segment and `sabrseg://<itag>/<sequence>` for
 * media segments (as published by [SabrNgDashMediaSource]'s generated DASH manifest).
 */
@OptIn(UnstableApi::class)
class SabrNgSegmentDataSource(
    private val session: SabrNgSession,
    private val segmentTimeoutMs: Long
) : DataSource {

    private var uri: Uri? = null
    private var data: ByteArray? = null
    private var dataStream: InputStream? = null
    private var pos = 0
    private var bytesRemaining: Long = 0

    override fun addTransferListener(transferListener: TransferListener) {
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        closeStream()
        data = null
        pos = maxOf(0, dataSpec.position).toInt()
        val itag = itagFromUri(dataSpec.uri)
        val lastSegment = dataSpec.uri.lastPathSegment
            ?: throw IOException("Bad SABR segment uri: ${dataSpec.uri}")

        val availableRemaining: Long
        if (lastSegment == "init") {
            val initData = session.getInitialization(itag) ?: ByteArray(0)
            data = initData
            availableRemaining = maxOf(0, initData.size - pos).toLong()
        } else {
            val sequence = try {
                lastSegment.toInt()
            } catch (e: NumberFormatException) {
                throw IOException("Bad SABR segment uri: ${dataSpec.uri}", e)
            }
            val segment = session.getMediaSegment(itag, sequence, segmentTimeoutMs)
            if (segment == null) {
                data = ByteArray(0)
                availableRemaining = 0
            } else {
                val stream = segment.openStream()
                dataStream = stream
                val skipped = skipFully(stream, maxOf(0, dataSpec.position))
                pos = minOf(Int.MAX_VALUE.toLong(), skipped).toInt()
                availableRemaining = maxOf(0, segment.getLength() - skipped)
            }
        }
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            availableRemaining
        } else {
            minOf(dataSpec.length, availableRemaining)
        }
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }
        if (bytesRemaining <= 0) {
            return C.RESULT_END_OF_INPUT
        }
        val currentData = data
        if (currentData != null) {
            if (pos >= currentData.size) {
                return C.RESULT_END_OF_INPUT
            }
            val toCopy =
                minOf(minOf(length.toLong(), (currentData.size - pos).toLong()), bytesRemaining)
                    .toInt()
            System.arraycopy(currentData, pos, target, offset, toCopy)
            pos += toCopy
            bytesRemaining -= toCopy
            return toCopy
        }
        val stream = dataStream ?: return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = stream.read(target, offset, toRead)
        if (read < 0) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }
        bytesRemaining -= read
        return read
    }

    private fun itagFromUri(u: Uri): Int {
        val host = u.host ?: throw IOException("Bad SABR segment uri without itag: $u")
        return try {
            host.toInt()
        } catch (e: NumberFormatException) {
            throw IOException("Bad SABR segment itag in uri: $u", e)
        }
    }

    @Throws(IOException::class)
    private fun closeStream() {
        dataStream?.let {
            it.close()
            dataStream = null
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        data = null
        try {
            closeStream()
        } catch (e: IOException) {
            // best-effort close
        }
    }

    private companion object {
        @Throws(IOException::class)
        private fun skipFully(input: InputStream, requested: Long): Long {
            var remaining = requested
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    continue
                }
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) {
                    break
                }
                remaining -= read
            }
            return requested - remaining
        }
    }
}
