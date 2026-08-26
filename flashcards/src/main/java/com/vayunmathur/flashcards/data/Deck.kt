package com.vayunmathur.flashcards.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.ReorderableDatabaseItem
import kotlinx.serialization.Serializable

/**
 * A deck of [Card]s plus its per-deck study configuration.
 *
 * [newPerDay]/[maxReviewsPerDay] cap how many cards a session introduces, and
 * [desiredRetention] (0..1) is the FSRS target recall probability that drives the
 * scheduled intervals. [fsrsWeights] holds a JSON array of 19 FSRS doubles
 * (empty = use `Scheduler.DEFAULT_W`) and [leechThreshold] is the number of lapses
 * before a card is auto-suspended as a leech (0 = disabled).
 */
@Serializable
@Entity
data class Deck(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String,
    val newPerDay: Int = 20,
    val maxReviewsPerDay: Int = 200,
    val desiredRetention: Double = 0.9,
    val fsrsWeights: String = "",
    val leechThreshold: Int = 8,
    override val position: Double = 0.0,
) : ReorderableDatabaseItem<Deck> {
    override fun withPosition(position: Double) = copy(position = position)
}
