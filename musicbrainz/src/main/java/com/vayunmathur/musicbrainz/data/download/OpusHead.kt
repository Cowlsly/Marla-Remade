package com.vayunmathur.musicbrainz.data.download

/**
 * The Opus identification header, and the packet arithmetic the Ogg writer needs.
 *
 * An Ogg/Opus file opens with a 19-byte `OpusHead` describing the channel count, the
 * pre-skip and the source rate. When the platform Opus encoder is driving, that has to come
 * from the encoder rather than being invented, because only the encoder knows how much
 * lookahead it introduced - get the pre-skip wrong and every player reports a duration a
 * few milliseconds out and starts playback slightly early.
 *
 * Android does not hand it over in the split `csd-0`/`csd-1`/`csd-2` form an extractor
 * produces. The platform encoder packs the header, the codec delay and the seek pre-roll
 * into one codec-config buffer as three `AOPUS...` marker chunks, so [fromCodecConfig]
 * accepts either shape, and [build] covers a codec that supplies neither.
 */
internal object OpusHead {

    const val SIZE = 19
    const val SAMPLE_RATE = 48_000

    private const val MAGIC = "OpusHead"
    private const val HEADER_MARKER = "AOPUSHDR"
    private const val MARKER_SIZE = 8
    private const val CHUNK_LENGTH_SIZE = 8

    /**
     * The Opus encoder's lookahead at 48 kHz, used only when a codec reports no header at
     * all. `libopus` returns 6.5 ms for every configuration the encoder is used in here.
     */
    const val DEFAULT_PRE_SKIP = 312

    fun build(channels: Int, preSkip: Int, inputSampleRate: Int): ByteArray {
        val head = ByteArray(SIZE)
        MAGIC.toByteArray(Charsets.ISO_8859_1).copyInto(head)
        head[8] = 1 // version
        head[9] = channels.toByte()
        head[10] = preSkip.toByte()
        head[11] = (preSkip ushr 8).toByte()
        OggPages.writeIntLe(head, 12, inputSampleRate)
        // Output gain 0 dB at 16..17, channel mapping family 0 at 18, both already zero.
        return head
    }

    /**
     * Pulls the identification header out of a codec-config buffer, or returns null when
     * there is nothing recognisable in it.
     */
    fun fromCodecConfig(config: ByteArray): ByteArray? {
        if (startsWith(config, 0, MAGIC)) {
            return config.takeIf { it.size >= SIZE }?.copyOfRange(0, SIZE)
        }
        if (startsWith(config, 0, HEADER_MARKER)) {
            val lengthAt = MARKER_SIZE
            val payloadAt = lengthAt + CHUNK_LENGTH_SIZE
            if (config.size < payloadAt) return null
            val length = readLongLe(config, lengthAt)
            if (length < SIZE || payloadAt + length > config.size) return null
            val head = config.copyOfRange(payloadAt, payloadAt + length.toInt())
            return head.takeIf { startsWith(it, 0, MAGIC) }
        }
        return null
    }

    fun preSkipOf(head: ByteArray): Int =
        (head[10].toInt() and 0xff) or ((head[11].toInt() and 0xff) shl 8)

    /**
     * How many 48 kHz samples one Opus packet decodes to, from its table-of-contents byte.
     *
     * Read rather than assumed, because the granule positions in the Ogg pages are what
     * decide the file's reported duration and how it seeks, and an encoder is free to pick
     * its own frame size.
     */
    fun packetSamples(packet: ByteArray, size: Int): Int {
        if (size < 1) return 0
        val toc = packet[0].toInt() and 0xff
        val config = toc ushr 3
        val frameSamples = when {
            // SILK and hybrid modes: 10, 20, 40 or 60 ms, chosen by the low two bits.
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
            config < 16 -> intArrayOf(480, 960)[config and 1]
            // CELT: 2.5, 5, 10 or 20 ms.
            else -> intArrayOf(120, 240, 480, 960)[config and 3]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            // An arbitrary-frame packet carries the count in the low six bits of byte one.
            else -> if (size < 2) return 0 else packet[1].toInt() and 0x3f
        }
        return frameSamples * frames
    }

    private fun startsWith(buffer: ByteArray, offset: Int, text: String): Boolean {
        if (buffer.size < offset + text.length) return false
        for (i in text.indices) {
            if (buffer[offset + i] != text[i].code.toByte()) return false
        }
        return true
    }

    private fun readLongLe(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((buffer[offset + i].toLong() and 0xff) shl (8 * i))
        return value
    }
}
