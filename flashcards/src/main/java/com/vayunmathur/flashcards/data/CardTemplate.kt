package com.vayunmathur.flashcards.data

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A card template of a [NoteType]. [qfmt]/[afmt] are the question/answer formats:
 * **markdown** text with `{{Field}}`, `{{FrontSide}}`, `{{cloze:Field}}` and
 * `{{#Field}}…{{/Field}}` / `{{^Field}}…{{/Field}}` placeholders, rendered by
 * `TemplateEngine`. [ord] is the 0-based template index; ([noteTypeId], [ord]) is
 * unique. For cloze note types there is a single template and the effective ord of
 * a generated card is the cloze number minus one.
 */
@Serializable
@Entity(
    indices = [Index(value = ["noteTypeId", "ord"], unique = true)],
)
data class CardTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteTypeId: Long,
    val ord: Int,
    val name: String,
    val qfmt: String,
    val afmt: String,
)
