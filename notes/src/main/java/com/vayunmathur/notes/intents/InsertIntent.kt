package com.vayunmathur.notes.intents

import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NotesRepository
import kotlinx.serialization.builtins.serializer

class InsertIntent: AssistantIntent<NoteData, Unit>(NoteData.serializer(), Unit.serializer()) {

    override suspend fun performCalculation(input: NoteData) {
        val repo = NotesRepository.get(this)
        repo.upsert(Note(title = input.title, content = input.content))
    }
}
