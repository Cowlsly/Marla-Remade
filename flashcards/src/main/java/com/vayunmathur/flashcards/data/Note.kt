package com.vayunmathur.flashcards.data

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.ReorderableDatabaseItem
import kotlinx.serialization.Serializable

/** The Anki field separator that joins a note's field values in [Note.flds]. */
const val FIELD_SEPARATOR = "\u001f"

/**
 * A note: the authored content that generates one or more [Card]s. It belongs to
 * a [NoteType] (which defines its fields and templates) and a [Deck].
 *
 * [flds] holds the field values joined by [FIELD_SEPARATOR], in field-ord order.
 * [sortField] is a copy of the first field (for the note list / sorting). [tags]
 * are space-separated. [guid] is a stable identifier preserved across `.apkg`
 * round-trips. [mod] is the last-modified epoch seconds (Anki convention).
 */
@Serializable
@Entity(
    indices = [Index("noteTypeId"), Index("deckId")],
)
data class Note(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val noteTypeId: Long,
    val deckId: Long,
    val guid: String,
    val flds: String,
    val sortField: String,
    val tags: String = "",
    val mod: Long = 0,
    override val position: Double = 0.0,
) : ReorderableDatabaseItem<Note> {
    override fun withPosition(position: Double) = copy(position = position)

    /** The individual field values, split from [flds]. */
    val fieldList: List<String> get() = flds.split(FIELD_SEPARATOR)

    /**
     * Maps the given [fields] (ordered by ord) to this note's values. Extra fields
     * map to empty strings; extra values are dropped.
     */
    fun fieldValues(fields: List<NoteTypeField>): Map<String, String> {
        val values = fieldList
        return fields.sortedBy { it.ord }
            .mapIndexed { index, field -> field.name to (values.getOrNull(index) ?: "") }
            .toMap()
    }
}
