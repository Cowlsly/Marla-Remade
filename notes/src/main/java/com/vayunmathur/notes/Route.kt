package com.vayunmathur.notes

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object NotesList: Route
    @Serializable
    data class Note(val id: Long): Route
    @Serializable
    data class ExternalNote(val uri: String): Route
}
