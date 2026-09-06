package com.vayunmathur.nowplaying.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * One stretch of time during which music was heard.
 *
 * A row is written when the detector latches on and updated when it latches off, so [endedAt] is
 * null exactly while the interval is still open. No audio is stored — only these timestamps and the
 * confidence that produced them.
 */
@Entity
data class DetectionEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch millis at which music was first detected. */
    val startedAt: Long,
    /** Epoch millis at which it stopped, or null while music is still playing. */
    val endedAt: Long? = null,
    /** Highest smoothed probability reached during the interval, in `0f..1f`. */
    val peakConfidence: Float = 0f,
)
