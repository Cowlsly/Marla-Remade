package com.vayunmathur.flashcards.data

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.ReorderableDatabaseItem
import kotlinx.serialization.Serializable

/** FSRS learning states stored in [Card.state]. */
object CardState {
    const val NEW = 0
    const val LEARNING = 1
    const val REVIEW = 2
    const val RELEARNING = 3
}

/**
 * A single card generated from a [Note] via one of its note type's templates, plus
 * its FSRS spaced-repetition memory state.
 *
 * The displayed content is *not* stored here: it is rendered on demand from the
 * owning [Note] and the template selected by [templateOrd] (for standard note types
 * this is the template index; for cloze note types it is the cloze number minus one).
 *
 * FSRS bookkeeping ([stability], [difficulty], [state], [lastReview], [lapses],
 * [reps]) is updated by `Scheduler.schedule` each time the card is graded. The
 * legacy SM-2 columns ([easeFactor], [intervalDays], [repetitions]) are retained
 * for safety and are otherwise unused.
 */
@Serializable
@Entity(
    indices = [
        Index(value = ["noteId", "templateOrd"], unique = true),
        Index("deckId"),
    ],
)
data class Card(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val noteId: Long,
    val templateOrd: Int,
    val deckId: Long,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val state: Int = CardState.NEW,
    val lastReview: Long = 0,
    val lapses: Int = 0,
    val reps: Int = 0,
    val dueDate: Long = 0,
    val easeFactor: Double = 2.5,
    val intervalDays: Int = 0,
    val repetitions: Int = 0,
    /** 0 = active, 1 = suspended (excluded from every study queue). */
    val suspended: Int = 0,
    override val position: Double = 0.0,
) : ReorderableDatabaseItem<Card> {
    override fun withPosition(position: Double) = copy(position = position)

    /** True while the card has never been graded. */
    val isNew: Boolean get() = state == CardState.NEW

    /** True while the card is suspended and excluded from study. */
    val isSuspended: Boolean get() = suspended == 1
}
