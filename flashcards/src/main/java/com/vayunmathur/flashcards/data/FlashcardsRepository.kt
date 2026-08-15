package com.vayunmathur.flashcards.data

import android.content.Context
import android.net.Uri
import com.vayunmathur.flashcards.util.ApkgImport
import com.vayunmathur.flashcards.util.CardGenerator
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single owner of [FlashcardsDatabase]. All DB access goes through here so the
 * process builds the database exactly once (via [RoomRepository]'s lazy [db]).
 */
class FlashcardsRepository private constructor(context: Context) :
    RoomRepository<FlashcardsDatabase>(context, FlashcardsDatabase::class, DB_NAME) {

    private val deckDao get() = db.deckDao()
    private val cardDao get() = db.cardDao()
    private val reviewLogDao get() = db.reviewLogDao()
    private val noteTypeDao get() = db.noteTypeDao()
    private val noteTypeFieldDao get() = db.noteTypeFieldDao()
    private val cardTemplateDao get() = db.cardTemplateDao()
    private val noteDao get() = db.noteDao()

    // ------------------------------------------------------------------
    // Read flows (cold)
    // ------------------------------------------------------------------

    val decks: Flow<List<Deck>> get() = deckDao.getAllFlow()
    val cards: Flow<List<Card>> get() = cardDao.getAllFlow()
    val notes: Flow<List<Note>> get() = noteDao.getAllFlow()
    val noteTypes: Flow<List<NoteType>> get() = noteTypeDao.getAllFlow()
    val noteTypeFields: Flow<List<NoteTypeField>> get() = noteTypeFieldDao.getAllFlow()
    val cardTemplates: Flow<List<CardTemplate>> get() = cardTemplateDao.getAllFlow()
    val reviewLogs: Flow<List<ReviewLog>> get() = reviewLogDao.getAllFlow()

    fun notesFor(deckId: Long): Flow<List<Note>> = noteDao.getByDeckFlow(deckId)
    fun noteById(id: Long): Flow<Note?> = noteDao.getByIdFlow(id)
    fun cardsFor(deckId: Long): Flow<List<Card>> = cardDao.getByDeckFlow(deckId)
    fun cardById(id: Long): Flow<Card?> = cardDao.getByIdFlow(id)
    fun dueCardsFor(deckId: Long, now: Long): Flow<List<Card>> = cardDao.getDueByDeckFlow(deckId, now)
    fun reviewLogsFor(deckId: Long?): Flow<List<ReviewLog>> =
        if (deckId == null) reviewLogDao.getAllFlow() else reviewLogDao.getByDeckFlow(deckId)

    // ------------------------------------------------------------------
    // Deck
    // ------------------------------------------------------------------

    suspend fun getAllDecks(): List<Deck> = deckDao.getAll()
    suspend fun getDeck(id: Long): Deck? = deckDao.getById(id)
    suspend fun upsertDeck(deck: Deck): Long = deckDao.upsert(deck)
    suspend fun deleteDeck(deck: Deck): Int = deckDao.delete(deck)

    // ------------------------------------------------------------------
    // NoteType
    // ------------------------------------------------------------------

    suspend fun getAllNoteTypes(): List<NoteType> = noteTypeDao.getAll()
    suspend fun getNoteType(id: Long): NoteType? = noteTypeDao.getById(id)
    suspend fun upsertNoteType(value: NoteType): Long = noteTypeDao.upsert(value)
    suspend fun deleteNoteType(value: NoteType): Int = noteTypeDao.delete(value)

    // ------------------------------------------------------------------
    // NoteTypeField
    // ------------------------------------------------------------------

    suspend fun getAllNoteTypeFields(): List<NoteTypeField> = noteTypeFieldDao.getAll()
    suspend fun getFieldsForNoteType(noteTypeId: Long): List<NoteTypeField> =
        noteTypeFieldDao.getByNoteType(noteTypeId)
    suspend fun upsertNoteTypeField(value: NoteTypeField): Long = noteTypeFieldDao.upsert(value)
    suspend fun upsertNoteTypeFields(values: List<NoteTypeField>) = noteTypeFieldDao.upsertAll(values)
    suspend fun deleteFieldsByNoteType(noteTypeId: Long) = noteTypeFieldDao.deleteByNoteType(noteTypeId)

    // ------------------------------------------------------------------
    // CardTemplate
    // ------------------------------------------------------------------

    suspend fun getAllCardTemplates(): List<CardTemplate> = cardTemplateDao.getAll()
    suspend fun getTemplatesForNoteType(noteTypeId: Long): List<CardTemplate> =
        cardTemplateDao.getByNoteType(noteTypeId)
    suspend fun upsertCardTemplate(value: CardTemplate): Long = cardTemplateDao.upsert(value)
    suspend fun upsertCardTemplates(values: List<CardTemplate>) = cardTemplateDao.upsertAll(values)
    suspend fun deleteTemplatesByNoteType(noteTypeId: Long) = cardTemplateDao.deleteByNoteType(noteTypeId)

    // ------------------------------------------------------------------
    // Note
    // ------------------------------------------------------------------

    suspend fun getAllNotes(): List<Note> = noteDao.getAll()
    suspend fun getNotesByDeck(deckId: Long): List<Note> = noteDao.getByDeck(deckId)
    suspend fun getNotesByNoteType(noteTypeId: Long): List<Note> = noteDao.getByNoteType(noteTypeId)
    suspend fun getNote(id: Long): Note? = noteDao.getById(id)
    suspend fun getNotesByIds(ids: List<Long>): List<Note> = noteDao.getByIds(ids)
    suspend fun upsertNote(value: Note): Long = noteDao.upsert(value)
    suspend fun upsertNotes(values: List<Note>) = noteDao.upsertAll(values)
    suspend fun deleteNote(value: Note): Int = noteDao.delete(value)
    suspend fun deleteNotesByIds(ids: List<Long>) = noteDao.deleteByIds(ids)
    suspend fun deleteNotesByDeck(deckId: Long) = noteDao.deleteByDeck(deckId)

    // ------------------------------------------------------------------
    // Card
    // ------------------------------------------------------------------

    suspend fun getAllCards(): List<Card> = cardDao.getAll()
    suspend fun getCardsByDeck(deckId: Long): List<Card> = cardDao.getByDeck(deckId)
    suspend fun getCardsByIds(ids: List<Long>): List<Card> = cardDao.getByIds(ids)
    suspend fun getCardsByNote(noteId: Long): List<Card> = cardDao.getByNote(noteId)
    suspend fun getCardsByNotes(noteIds: List<Long>): List<Card> = cardDao.getByNotes(noteIds)
    suspend fun upsertCard(value: Card): Long = cardDao.upsert(value)
    suspend fun upsertCards(values: List<Card>) = cardDao.upsertAll(values)
    suspend fun deleteCard(value: Card): Int = cardDao.delete(value)
    suspend fun setCardsSuspended(ids: List<Long>, value: Int) = cardDao.setSuspended(ids, value)
    suspend fun setCardsSuspendedByNotes(noteIds: List<Long>, value: Int) =
        cardDao.setSuspendedByNotes(noteIds, value)
    suspend fun moveCardsByNotes(noteIds: List<Long>, deckId: Long) = cardDao.moveByNotes(noteIds, deckId)
    suspend fun deleteCardsByDeck(deckId: Long) = cardDao.deleteByDeck(deckId)
    suspend fun deleteCardsByNote(noteId: Long) = cardDao.deleteByNote(noteId)
    suspend fun deleteCardsByNotes(noteIds: List<Long>) = cardDao.deleteByNotes(noteIds)
    suspend fun deleteRemovedTemplates(noteId: Long, keepOrds: List<Int>) =
        cardDao.deleteRemovedTemplates(noteId, keepOrds)

    /** Wrapper over [CardGenerator.regenerate] using this repository's [CardDao]. */
    suspend fun regenerateCards(
        note: Note,
        noteType: NoteType,
        templates: List<CardTemplate>,
        fields: List<NoteTypeField>,
    ) = CardGenerator.regenerate(note, noteType, templates, fields, cardDao)

    // ------------------------------------------------------------------
    // ReviewLog
    // ------------------------------------------------------------------

    suspend fun getReviewLogsByDeckOrdered(deckId: Long): List<ReviewLog> =
        reviewLogDao.getByDeckOrdered(deckId)
    suspend fun getReviewLogsByCard(cardId: Long): List<ReviewLog> = reviewLogDao.getByCard(cardId)
    suspend fun insertReviewLog(value: ReviewLog): Long = reviewLogDao.insert(value)
    suspend fun deleteReviewLogById(id: Long) = reviewLogDao.deleteById(id)
    suspend fun deleteReviewLogsByCards(cardIds: List<Long>) = reviewLogDao.deleteByCards(cardIds)
    suspend fun deleteReviewLogsByDeck(deckId: Long) = reviewLogDao.deleteByDeck(deckId)

    // ------------------------------------------------------------------
    // Apkg import (delegates to [ApkgImport] with this repository's DAOs)
    // ------------------------------------------------------------------

    suspend fun importApkg(context: Context, uri: Uri): String =
        ApkgImport.import(context, uri, deckDao, noteTypeDao, noteTypeFieldDao, cardTemplateDao, noteDao, cardDao)

    companion object {
        @Volatile
        private var instance: FlashcardsRepository? = null

        fun get(context: Context): FlashcardsRepository =
            instance ?: synchronized(this) {
                instance ?: FlashcardsRepository(context).also { instance = it }
            }
    }
}
