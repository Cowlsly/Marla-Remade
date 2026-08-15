package com.vayunmathur.notes.platform

import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteBlock

/**
 * The UI contract between [NotesViewModel] plus the nav back stack and the screens.
 *
 * Screens take a state value and an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, and the binders in `ui` implement these interfaces.
 */

/** Everything the notes list draws. */
data class NotesListUiState(
    val notes: List<Note> = emptyList(),
    /** Hidden while a note is already open beside the list in the list-detail layout. */
    val showAddButton: Boolean = true,
)

/**
 * Notes list callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface NotesListActions {
    fun openNote(id: Long) {}
    fun createNote() {}
    fun delete(note: Note) {}

    /** Persists a reordered list; each note already carries its new position. */
    fun upsertAll(notes: List<Note>) {}

    companion object {
        val Noop: NotesListActions = object : NotesListActions {}
    }
}

/** Everything the note editor draws: the title plus the ordered body blocks. */
data class NoteUiState(
    val title: String = "",
    val blocks: List<NoteBlock> = emptyList(),
)

/** Note editor callbacks. Same no-op-default arrangement as [NotesListActions]. */
interface NoteActions {
    fun back() {}
    fun setTitle(title: String) {}

    /** The text block [id] was edited to [markdown]. */
    fun setBlockMarkdown(id: String, markdown: String) {}

    /** Insert media just below [afterBlockId] (the focused text block), or at the end. */
    fun addImage(afterBlockId: String?) {}
    fun addDrawing(afterBlockId: String?) {}

    fun editDrawing(block: NoteBlock.Ink) {}
    fun resizeImage(id: String, widthFraction: Float) {}

    /** Moves a block [delta] places: -1 up, +1 down. */
    fun moveBlock(id: String, delta: Int) {}
    fun deleteBlock(id: String) {}

    fun copyNote() {}
    fun shareNote() {}
    fun deleteNote() {}

    companion object {
        val Noop: NoteActions = object : NoteActions {}
    }
}
