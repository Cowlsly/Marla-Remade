package com.vayunmathur.flashcards.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/** [NoteType.type] values (mirrors Anki's `models[*].type`). */
object NoteTypeKind {
    const val STANDARD = 0
    const val CLOZE = 1
}

/**
 * A note "model" (in Anki terms): a named set of [NoteTypeField]s plus a set of
 * [CardTemplate]s that turn a [Note]'s field values into rendered cards.
 *
 * [type] is [NoteTypeKind.STANDARD] or [NoteTypeKind.CLOZE]. [css] is the model's
 * stylesheet, carried through `.apkg` import/export but unused by the markdown
 * display pipeline. [mod] is the last-modified epoch seconds (Anki convention).
 */
@Serializable
@Entity
data class NoteType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: Int = NoteTypeKind.STANDARD,
    val css: String = "",
    val mod: Long = 0,
)
