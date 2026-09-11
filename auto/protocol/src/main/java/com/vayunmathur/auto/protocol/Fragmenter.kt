package com.vayunmathur.auto.protocol

/** One slice of a message payload, with the frame flags that describe its position. */
data class Fragment(
    val offset: Int,
    val length: Int,
    val isFirst: Boolean,
    val isLast: Boolean,
) {
    /** Frame flags for this slice, before [FrameFlags.CONTROL]/[FrameFlags.ENCRYPTED]. */
    val positionFlags: Int
        get() = (if (isFirst) FrameFlags.FIRST else 0) or (if (isLast) FrameFlags.LAST else 0)
}

/**
 * Splits a message payload across frames.
 *
 * The usable payload differs between frames because the first fragment of a *fragmented*
 * message carries an 8-byte header (it has the 4-byte total length) while every other
 * frame carries 4. A message that fits in one frame is `FIRST or LAST` and so uses the
 * short header.
 *
 * Sizes here are plaintext. When the payload is subsequently TLS-wrapped the record grows,
 * so [FrameHeader.DEFAULT_MAX_FRAME_SIZE] (16128) is deliberately below gearhead's 16384
 * receive staging buffer, leaving 256 bytes of headroom for the record overhead.
 */
object Fragmenter {

    /**
     * @throws IllegalArgumentException if [isControl] and the payload needs more than one
     *   frame. Gearhead refuses to fragment control messages (`rtt.i()`), so a control
     *   message that does not fit is a programming error rather than something to split.
     */
    fun split(
        payloadSize: Int,
        maxFrameSize: Int = FrameHeader.DEFAULT_MAX_FRAME_SIZE,
        isControl: Boolean = false,
    ): List<Fragment> {
        require(payloadSize >= 0) { "payload size $payloadSize is negative" }
        require(maxFrameSize > FrameHeader.LONG_HEADER_SIZE) {
            "max frame size $maxFrameSize leaves no room for a payload"
        }

        val single = maxFrameSize - FrameHeader.SHORT_HEADER_SIZE
        if (payloadSize <= single) {
            return listOf(Fragment(0, payloadSize, isFirst = true, isLast = true))
        }
        require(!isControl) {
            "control message of $payloadSize bytes exceeds the $single-byte frame payload " +
                "and control messages cannot be fragmented"
        }

        val fragments = mutableListOf<Fragment>()
        // The first fragment pays for the 4-byte total length; the rest do not.
        var offset = minOf(payloadSize, maxFrameSize - FrameHeader.LONG_HEADER_SIZE)
        fragments += Fragment(0, offset, isFirst = true, isLast = false)
        while (offset < payloadSize) {
            val length = minOf(payloadSize - offset, single)
            offset += length
            fragments += Fragment(
                offset = offset - length,
                length = length,
                isFirst = false,
                isLast = offset == payloadSize,
            )
        }
        return fragments
    }
}
