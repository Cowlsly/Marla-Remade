package com.vayunmathur.flashcards.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One row per grade, written whenever a card is reviewed. Powers the statistics
 * screen (review counts, retention, streak) and any future FSRS optimization.
 *
 * [grade] is the 1..4 FSRS grade, [elapsedDays] the time since the previous
 * review, [scheduledDays] the interval assigned by this review, and [state] the
 * card's resulting FSRS state.
 */
@Serializable
@Entity
data class ReviewLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val deckId: Long,
    val reviewedAt: Long,
    val grade: Int,
    val elapsedDays: Double,
    val scheduledDays: Double,
    val state: Int,
)
