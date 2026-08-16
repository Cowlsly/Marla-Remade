package org.schabi.newpipe.extractor.services.youtube.sabrng.media

import org.brotli.dec.BrotliInputStream
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrResponse
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrRecoverableException
import org.schabi.newpipe.extractor.services.youtube.sabrng.generated.SabrMediaHeader
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrResponseDecoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/** Assembles UMP MEDIA_HEADER / MEDIA / MEDIA_END parts into complete [SabrMediaSegment]s. */
object SabrMediaSegmentCollector {
    private const val MIN_PROGRESSIVE_SEGMENT_BYTES = 64 * 1024

    @JvmStatic
    @Throws(SabrProtocolException::class)
    fun collect(response: YoutubeSabrResponse): List<SabrMediaSegment> {
        val segments = ArrayList<SabrMediaSegment>()
        val openSegments = HashMap<Int, OpenSegment>()
        for (part in response.getParts()) {
            val partData = part.getRawData()
            when (part.getType()) {
                SabrResponseDecoder.MEDIA_HEADER -> {
                    val header = SabrMediaHeader.decode(partData)
                    openSegments[header.getHeaderId()] = OpenSegment(header)
                }
                SabrResponseDecoder.MEDIA -> {
                    if (partData.isNotEmpty()) {
                        val openSegment = openSegments[partData[0].toInt() and 0xff]
                        openSegment?.write(partData, 1, partData.size - 1)
                    }
                }
                SabrResponseDecoder.MEDIA_END -> {
                    if (partData.isNotEmpty()) {
                        val openSegment = openSegments.remove(partData[0].toInt() and 0xff)
                        if (openSegment != null) {
                            segments.add(openSegment.toSegment())
                        }
                    }
                }
                else -> {}
            }
        }
        return segments
    }

    /**
     * Incremental collector for the streaming path: feed MEDIA_HEADER / MEDIA / MEDIA_END parts as
     * they arrive and get each completed segment back from [onMediaEnd], so the caller never has to
     * retain all the MEDIA parts at once (that whole-body buffering was the 4K OOM).
     */
    class Incremental @JvmOverloads constructor(private val spoolDirectory: File? = null) {
        private val openSegments = HashMap<Int, OpenSegment>()

        @Throws(SabrProtocolException::class)
        fun onMediaHeader(partData: ByteArray): SabrMediaSegment? {
            val header = SabrMediaHeader.decode(partData)
            val current = OpenSegment(header, spoolDirectory)
            val previous = openSegments.put(header.getHeaderId(), current)
            previous?.abort()
            return current.getProgressiveSegment()
        }

        @Throws(SabrProtocolException::class)
        fun onMedia(partData: ByteArray) {
            if (partData.isNotEmpty()) {
                val openSegment = openSegments[partData[0].toInt() and 0xff]
                openSegment?.write(partData, 1, partData.size - 1)
            }
        }

        @Throws(SabrProtocolException::class)
        fun onMedia(headerId: Int, input: InputStream, count: Int) {
            val openSegment = openSegments[headerId]
            if (openSegment != null) {
                openSegment.write(input, count)
            } else {
                drain(input, count)
            }
        }

        @Throws(SabrProtocolException::class)
        fun onMediaEnd(partData: ByteArray): SabrMediaSegment? {
            if (partData.isNotEmpty()) {
                val openSegment = openSegments.remove(partData[0].toInt() and 0xff)
                if (openSegment != null) {
                    try {
                        return openSegment.toSegment()
                    } catch (e: SabrProtocolException) {
                        openSegment.abort()
                        throw e
                    }
                }
            }
            return null
        }

        fun abort() {
            for (segment in openSegments.values) {
                segment.abort()
            }
            openSegments.clear()
        }
    }

    private class OpenSegment @Throws(SabrProtocolException::class) constructor(
        private val header: SabrMediaHeader,
        spoolDirectory: File? = null
    ) {
        private val fixedData: ByteArray?
        private val dynamicData: ByteArrayOutputStream?
        private val file: File?
        private val fileOutput: OutputStream?
        private val progressiveSegment: SabrMediaSegment?
        private var length = 0
        private var fileOutputClosed = false

        init {
            val contentLength = header.getContentLength()
            if (spoolDirectory != null && header.getCompressionAlgorithm() <= 0 &&
                !header.isInitSegment()
            ) {
                if (contentLength > Int.MAX_VALUE) {
                    throw SabrProtocolException(
                        "SABR media segment too large: headerId=" + header.getHeaderId() +
                            ", length=" + contentLength
                    )
                }
                if (!spoolDirectory.exists() && !spoolDirectory.mkdirs()) {
                    throw SabrRecoverableException(
                        "Could not create SABR spool directory: $spoolDirectory"
                    )
                }
                try {
                    val spoolFile = File.createTempFile(
                        "sabr-" + header.getItag() + '-' + header.getSequenceNumber() + '-',
                        ".seg", spoolDirectory
                    )
                    file = spoolFile
                    fileOutput = FileOutputStream(spoolFile)
                    progressiveSegment = if (contentLength < MIN_PROGRESSIVE_SEGMENT_BYTES) {
                        null
                    } else {
                        SabrMediaSegment.InternalAccess.progressive(
                            header, spoolFile, contentLength.toInt()
                        )
                    }
                } catch (e: IOException) {
                    throw SabrRecoverableException("Could not open SABR spool file", e)
                }
                fixedData = null
                dynamicData = null
            } else if (contentLength >= 0) {
                if (contentLength > Int.MAX_VALUE) {
                    throw SabrProtocolException(
                        "SABR media segment too large: headerId=" + header.getHeaderId() +
                            ", length=" + contentLength
                    )
                }
                fixedData = ByteArray(contentLength.toInt())
                dynamicData = null
                file = null
                fileOutput = null
                progressiveSegment = null
            } else {
                fixedData = null
                dynamicData = ByteArrayOutputStream()
                file = null
                fileOutput = null
                progressiveSegment = null
            }
        }

        fun getProgressiveSegment(): SabrMediaSegment? = progressiveSegment

        @Throws(SabrProtocolException::class)
        fun write(bytes: ByteArray, offset: Int, count: Int) {
            if (count <= 0) {
                return
            }
            ensureLengthFits(count)
            ensureExpectedLengthNotExceeded(count)
            if (fixedData != null) {
                if (length + count > fixedData.size) {
                    throw SabrRecoverableException(
                        "SABR media length overflow: headerId=" + header.getHeaderId() +
                            ", expected=" + fixedData.size + ", actual>=" + (length + count)
                    )
                }
                System.arraycopy(bytes, offset, fixedData, length, count)
            } else if (fileOutput != null) {
                try {
                    fileOutput.write(bytes, offset, count)
                    length += count
                    if (progressiveSegment != null) {
                        SabrMediaSegment.InternalAccess.onBytesWritten(progressiveSegment, count)
                    }
                } catch (e: IOException) {
                    throw SabrRecoverableException("Could not write SABR spool file", e)
                }
            } else {
                dynamicData!!.write(bytes, offset, count)
            }
            if (fileOutput == null) {
                length += count
            }
        }

        @Throws(SabrProtocolException::class)
        fun write(input: InputStream, count: Int) {
            if (count <= 0) {
                return
            }
            ensureLengthFits(count)
            if (isExpectedLengthExceeded(count)) {
                drain(input, count)
                throw lengthOverflowException(count)
            }
            if (fixedData != null) {
                if (length + count > fixedData.size) {
                    drain(input, count)
                    throw SabrRecoverableException(
                        "SABR media length overflow: headerId=" + header.getHeaderId() +
                            ", expected=" + fixedData.size + ", actual>=" + (length + count)
                    )
                }
                readFully(input, fixedData, length, count)
            } else {
                val buffer = ByteArray(8192)
                var remaining = count
                while (remaining > 0) {
                    val read: Int = try {
                        input.read(buffer, 0, Math.min(buffer.size, remaining))
                    } catch (e: IOException) {
                        throw SabrRecoverableException("Could not read SABR media payload", e)
                    }
                    if (read < 0) {
                        throw SabrRecoverableException("Unexpected EOF in SABR media payload")
                    }
                    if (fileOutput != null) {
                        try {
                            fileOutput.write(buffer, 0, read)
                            length += read
                            if (progressiveSegment != null) {
                                SabrMediaSegment.InternalAccess.onBytesWritten(
                                    progressiveSegment, read
                                )
                            }
                        } catch (e: IOException) {
                            throw SabrRecoverableException("Could not write SABR spool file", e)
                        }
                    } else {
                        dynamicData!!.write(buffer, 0, read)
                    }
                    remaining -= read
                }
            }
            if (fileOutput == null) {
                length += count
            }
        }

        @Throws(SabrProtocolException::class)
        fun toSegment(): SabrMediaSegment {
            if (fileOutput != null) {
                closeFileOutput()
                if (header.getContentLength() >= 0 && length.toLong() != header.getContentLength()) {
                    throw SabrRecoverableException(
                        "SABR media length mismatch: headerId=" + header.getHeaderId() +
                            ", expected=" + header.getContentLength() + ", actual=" + length
                    )
                }
                if (progressiveSegment != null) {
                    SabrMediaSegment.InternalAccess.completeProgressive(progressiveSegment)
                    return progressiveSegment
                }
                return SabrMediaSegment.InternalAccess.fromFile(header, file!!, length)
            }
            val rawBytes: ByteArray = fixedData ?: dynamicData!!.toByteArray()
            if (header.getContentLength() >= 0 && length.toLong() != header.getContentLength()) {
                throw SabrRecoverableException(
                    "SABR media length mismatch: headerId=" + header.getHeaderId() +
                        ", expected=" + header.getContentLength() + ", actual=" + length
                )
            }
            return SabrMediaSegment.InternalAccess.fromBytes(
                header, maybeDecompress(header, rawBytes)
            )
        }

        @Throws(SabrProtocolException::class)
        private fun ensureLengthFits(count: Int) {
            if (length > Int.MAX_VALUE - count) {
                throw SabrProtocolException(
                    "SABR media segment too large: headerId=" + header.getHeaderId() +
                        ", length>" + Int.MAX_VALUE
                )
            }
        }

        @Throws(SabrProtocolException::class)
        private fun ensureExpectedLengthNotExceeded(count: Int) {
            if (isExpectedLengthExceeded(count)) {
                throw lengthOverflowException(count)
            }
        }

        private fun isExpectedLengthExceeded(count: Int): Boolean =
            header.getContentLength() >= 0 && length + count.toLong() > header.getContentLength()

        private fun lengthOverflowException(count: Int): SabrRecoverableException =
            SabrRecoverableException(
                "SABR media length overflow: headerId=" + header.getHeaderId() +
                    ", expected=" + header.getContentLength() + ", actual>=" + (length + count.toLong())
            )

        fun abort() {
            if (progressiveSegment != null) {
                SabrMediaSegment.InternalAccess.failProgressive(
                    progressiveSegment,
                    IOException("SABR media segment ended before MEDIA_END")
                )
            }
            try {
                closeFileOutput()
            } catch (ignored: SabrProtocolException) {
                // Best-effort cleanup after an already-failing segment.
            }
            if (file != null && file.exists() && !file.delete()) {
                file.deleteOnExit()
            }
        }

        @Throws(SabrProtocolException::class)
        private fun closeFileOutput() {
            if (fileOutput == null || fileOutputClosed) {
                return
            }
            try {
                fileOutput.close()
                fileOutputClosed = true
            } catch (e: IOException) {
                throw SabrRecoverableException("Could not close SABR spool file", e)
            }
        }

        companion object {
            @Throws(SabrProtocolException::class)
            private fun maybeDecompress(header: SabrMediaHeader, bytes: ByteArray): ByteArray {
                val compressionAlgorithm = header.getCompressionAlgorithm()
                if (compressionAlgorithm <= 0) {
                    return bytes
                }
                if (compressionAlgorithm == 1) {
                    return gunzip(bytes)
                }
                if (compressionAlgorithm == 2) {
                    return brotli(bytes)
                }
                throw SabrProtocolException(
                    "Unsupported SABR media compression: $compressionAlgorithm"
                )
            }

            @Throws(SabrProtocolException::class)
            private fun gunzip(bytes: ByteArray): ByteArray {
                try {
                    GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                        ByteArrayOutputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                            return output.toByteArray()
                        }
                    }
                } catch (e: IOException) {
                    throw SabrRecoverableException(
                        "Could not decompress gzip SABR media segment", e
                    )
                }
            }

            @Throws(SabrProtocolException::class)
            private fun brotli(bytes: ByteArray): ByteArray {
                try {
                    BrotliInputStream(ByteArrayInputStream(bytes)).use { input ->
                        ByteArrayOutputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                            return output.toByteArray()
                        }
                    }
                } catch (e: IOException) {
                    throw SabrRecoverableException(
                        "Could not decompress brotli SABR media segment", e
                    )
                }
            }
        }
    }

    @Throws(SabrProtocolException::class)
    private fun readFully(input: InputStream, target: ByteArray, offset: Int, count: Int) {
        var current = offset
        var remaining = count
        while (remaining > 0) {
            val read: Int = try {
                input.read(target, current, remaining)
            } catch (e: IOException) {
                throw SabrRecoverableException("Could not read SABR media payload", e)
            }
            if (read < 0) {
                throw SabrRecoverableException("Unexpected EOF in SABR media payload")
            }
            current += read
            remaining -= read
        }
    }

    @Throws(SabrProtocolException::class)
    private fun drain(input: InputStream, count: Int) {
        val buffer = ByteArray(8192)
        var remaining = count
        while (remaining > 0) {
            val read: Int = try {
                input.read(buffer, 0, Math.min(buffer.size, remaining))
            } catch (e: IOException) {
                throw SabrRecoverableException("Could not drain SABR media payload", e)
            }
            if (read < 0) {
                throw SabrRecoverableException("Unexpected EOF in SABR media payload")
            }
            remaining -= read
        }
    }
}
