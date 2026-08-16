package org.schabi.newpipe.extractor.services.youtube.sabrng.media

import java.util.Collections

/** Sequence-indexed segment timeline parsed from a format's initialization data. */
class SabrSegmentIndex internal constructor(entries: List<Entry>) {
    private val entries: List<Entry> = Collections.unmodifiableList(ArrayList(entries))

    fun getEntry(sequenceNumber: Int): Entry? {
        if (sequenceNumber <= 0 || sequenceNumber > entries.size) {
            return null
        }
        return entries[sequenceNumber - 1]
    }

    fun size(): Int = entries.size

    class Entry internal constructor(
        private val sequenceNumber: Int,
        private val startMs: Long,
        private val durationMs: Long
    ) {
        fun getSequenceNumber(): Int = sequenceNumber
        fun getStartMs(): Long = startMs
        fun getDurationMs(): Long = durationMs
        fun getEndMs(): Long = startMs + durationMs
    }
}
