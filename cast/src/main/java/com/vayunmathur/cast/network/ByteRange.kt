package com.vayunmathur.cast.network

/**
 * A resolved `Range` request: the byte offsets to send, inclusive of both ends.
 *
 * [MediaFileServer] answers one of these with `206 Partial Content` and a
 * `Content-Range: bytes first-last/total` header.
 */
data class ByteRange(val first: Long, val last: Long) {
    val length: Long get() = last - first + 1
}

/** The three things a `Range` header can mean to a server that supports one range. */
sealed interface ByteRangeResult {
    /** No `Range` header, or one this server does not implement: send the whole entity. */
    data object Whole : ByteRangeResult

    data class Partial(val range: ByteRange) : ByteRangeResult

    /** The range is syntactically fine but outside the entity: answer `416`. */
    data object Unsatisfiable : ByteRangeResult
}

/**
 * Parse a `Range` header value against a known entity length.
 *
 * Chromecast probes with `HEAD` and then seeks with `Range`, and without a correct `206` the
 * seek bar does nothing - which is the whole reason this is a separate, pure function with
 * its own tests rather than a few lines inside the request loop.
 *
 * Deliberately narrow, per RFC 9110 6.1: only `bytes=` is understood, and only a single
 * range. A multi-range request (`bytes=0-9,20-29`) would need a `multipart/byteranges` body;
 * no receiver sends one, so it degrades to [ByteRangeResult.Whole] rather than being
 * answered wrongly. A malformed header does the same, which is what the spec requires -
 * an unparseable `Range` must be ignored, not rejected.
 *
 * The forms that do matter:
 *  - `bytes=N-M`  first N, last M
 *  - `bytes=N-`   from N to the end, which is what a player sends when it seeks
 *  - `bytes=-N`   the *last* N bytes, a suffix range
 */
fun parseByteRange(header: String?, totalLength: Long): ByteRangeResult {
    if (header == null || totalLength <= 0) return ByteRangeResult.Whole
    val trimmed = header.trim()
    if (!trimmed.startsWith("bytes=", ignoreCase = true)) return ByteRangeResult.Whole
    val spec = trimmed.substring("bytes=".length).trim()
    if (spec.contains(',')) return ByteRangeResult.Whole
    val dash = spec.indexOf('-')
    if (dash < 0) return ByteRangeResult.Whole
    val firstText = spec.substring(0, dash).trim()
    val lastText = spec.substring(dash + 1).trim()
    if (firstText.isEmpty()) {
        // Suffix range. A suffix longer than the entity is the whole entity, not a 416.
        val suffix = lastText.toLongOrNull() ?: return ByteRangeResult.Whole
        if (suffix <= 0) return ByteRangeResult.Unsatisfiable
        val first = (totalLength - suffix).coerceAtLeast(0)
        return ByteRangeResult.Partial(ByteRange(first, totalLength - 1))
    }
    val first = firstText.toLongOrNull() ?: return ByteRangeResult.Whole
    if (first < 0) return ByteRangeResult.Whole
    if (first >= totalLength) return ByteRangeResult.Unsatisfiable
    val last = if (lastText.isEmpty()) {
        totalLength - 1
    } else {
        val requested = lastText.toLongOrNull() ?: return ByteRangeResult.Whole
        if (requested < first) return ByteRangeResult.Unsatisfiable
        // A last-byte-pos past the end is clamped, not an error.
        requested.coerceAtMost(totalLength - 1)
    }
    return ByteRangeResult.Partial(ByteRange(first, last))
}
