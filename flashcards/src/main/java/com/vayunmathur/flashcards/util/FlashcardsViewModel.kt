package com.vayunmathur.flashcards.util

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.CardState
import com.vayunmathur.flashcards.data.CardTemplate
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.FlashcardsRepository
import com.vayunmathur.flashcards.data.FIELD_SEPARATOR
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.data.NoteType
import com.vayunmathur.flashcards.data.NoteTypeKind
import com.vayunmathur.flashcards.data.ReviewLog
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * ViewModel for the Flashcards app.
 *
 * Owns the deck/note/card [StateFlow]s collected by the screens, the cached
 * [noteTypes] (Anki-style models), the persisted [settings], and the in-memory
 * [ReviewSession] driving the review screen via [review]. Card content is rendered
 * on demand from a note + its template through [TemplateEngine]. Grading runs the
 * pure [Scheduler], upserts the card, and writes a [ReviewLog] row.
 */
class FlashcardsViewModel(
    application: Application,
    private val repository: FlashcardsRepository,
) : AndroidViewModel(application) {

    private val ds = DataStoreUtils.getInstance(application)

    val decks: StateFlow<List<Deck>> = repository.decks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All cards across every deck; the deck list derives per-deck counts from this. */
    val cards: StateFlow<List<Card>> = repository.cards
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All notes across every deck; the note-type manager derives per-type counts from this. */
    val notes: StateFlow<List<Note>> = repository.notes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every note type with its ordered fields and templates. */
    val noteTypes: StateFlow<List<NoteTypeWithConfig>> = combine(
        repository.noteTypes,
        repository.noteTypeFields,
        repository.cardTemplates,
    ) { types, fields, templates ->
        types.map { type ->
            NoteTypeWithConfig(
                noteType = type,
                fields = fields.filter { it.noteTypeId == type.id }.sortedBy { it.ord },
                templates = templates.filter { it.noteTypeId == type.id }.sortedBy { it.ord },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        launchIo { ensureBuiltInNoteTypes() }
    }

    fun notesFor(deckId: Long): Flow<List<Note>> = repository.notesFor(deckId)

    fun noteById(id: Long): Flow<Note?> = repository.noteById(id)

    fun cardsFor(deckId: Long): Flow<List<Card>> = repository.cardsFor(deckId)

    fun reviewLogsFor(deckId: Long?): Flow<List<ReviewLog>> = repository.reviewLogsFor(deckId)

    // -- Persisted settings ------------------------------------------------

    val settings: StateFlow<SettingsUiState> = combine(
        ds.doubleFlow(KEY_RETENTION),
        ds.longFlow(KEY_NEW_PER_DAY, 20L),
        ds.longFlow(KEY_MAX_REVIEWS, 200L),
        ds.longFlow(KEY_THEME_MODE, 0L),
    ) { retention, newPerDay, maxReviews, theme ->
        arrayOf<Any>(retention, newPerDay, maxReviews, theme)
    }
        .combine(ds.booleanFlow(KEY_REMINDER_ENABLED)) { core, enabled -> core to enabled }
        .combine(ds.longFlow(KEY_REMINDER_MINUTES, 20L * 60)) { (core, enabled), minutes ->
            Triple(core, enabled, minutes)
        }
        .combine(ds.booleanFlow(KEY_AUTO_PLAY)) { (core, enabled, minutes), autoPlay ->
            SettingsUiState(
                desiredRetention = (core[0] as Double).takeIf { it > 0.0 } ?: 0.9,
                newPerDay = (core[1] as Long).toInt(),
                maxReviews = (core[2] as Long).toInt(),
                themeMode = (core[3] as Long).toInt(),
                reminderEnabled = enabled,
                reminderHour = (minutes / 60).toInt(),
                reminderMinute = (minutes % 60).toInt(),
                autoPlay = autoPlay,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setDesiredRetention(value: Double) =
        launchIo { ds.setDouble(KEY_RETENTION, value) }

    fun setNewPerDay(value: Int) = launchIo { ds.setLong(KEY_NEW_PER_DAY, value.toLong()) }

    fun setMaxReviews(value: Int) = launchIo { ds.setLong(KEY_MAX_REVIEWS, value.toLong()) }

    fun setThemeMode(mode: Int) = launchIo { ds.setLong(KEY_THEME_MODE, mode.toLong()) }

    fun setAutoPlay(enabled: Boolean) = launchIo { ds.setBoolean(KEY_AUTO_PLAY, enabled) }

    // -- Deck presets ------------------------------------------------------

    val deckPresets: StateFlow<List<DeckPreset>> = ds.stringFlow(KEY_DECK_PRESETS)
        .map { parsePresets(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Saves the current study settings under [name] as a reusable preset. */
    fun saveDeckPreset(name: String) = launchIo {
        val clean = name.trim()
        if (clean.isEmpty()) return@launchIo
        val s = settings.value
        val map = parsePresets(ds.getString(KEY_DECK_PRESETS).orEmpty())
            .associateBy { it.name }
            .toMutableMap()
        map[clean] = DeckPreset(clean, s.newPerDay, s.maxReviews, s.desiredRetention)
        ds.setString(KEY_DECK_PRESETS, presetsToJson(map.values.toList()))
        AppMessages.show(getApplication<Application>().getString(R.string.preset_saved))
    }

    /** Applies preset [name] to the global defaults and every existing deck. */
    fun applyDeckPreset(name: String) = launchIo {
        val preset = parsePresets(ds.getString(KEY_DECK_PRESETS).orEmpty())
            .firstOrNull { it.name == name } ?: return@launchIo
        ds.setLong(KEY_NEW_PER_DAY, preset.newPerDay.toLong())
        ds.setLong(KEY_MAX_REVIEWS, preset.maxReviews.toLong())
        ds.setDouble(KEY_RETENTION, preset.desiredRetention)
        repository.getAllDecks().forEach { deck ->
            repository.upsertDeck(
                deck.copy(
                    newPerDay = preset.newPerDay,
                    maxReviewsPerDay = preset.maxReviews,
                    desiredRetention = preset.desiredRetention,
                ),
            )
        }
        AppMessages.show(getApplication<Application>().getString(R.string.preset_applied))
    }

    fun deleteDeckPreset(name: String) = launchIo {
        val remaining = parsePresets(ds.getString(KEY_DECK_PRESETS).orEmpty()).filterNot { it.name == name }
        ds.setString(KEY_DECK_PRESETS, presetsToJson(remaining))
    }

    // -- FSRS optimization -------------------------------------------------

    /**
     * Runs the pragmatic [FsrsOptimizer] on every deck with enough review history,
     * saving the tuned weights per deck. Reports the outcome via [AppMessages].
     */
    fun optimizeAllDecks() = launchIo {
        val ctx = getApplication<Application>()
        var optimizedReviews = 0
        var optimizedDecks = 0
        var mostReviews = 0
        repository.getAllDecks().forEach { deck ->
            val logs = repository.getReviewLogsByDeckOrdered(deck.id)
            mostReviews = maxOf(mostReviews, logs.size)
            val byCard = logs.groupBy { it.cardId }.values.toList()
            if (FsrsOptimizer.hasEnough(byCard)) {
                val weights = withContext(Dispatchers.Default) { FsrsOptimizer.optimize(byCard) }
                repository.upsertDeck(deck.copy(fsrsWeights = Scheduler.weightsToJson(weights)))
                optimizedReviews += logs.size
                optimizedDecks++
            }
        }
        if (optimizedDecks > 0) {
            AppMessages.show(ctx.getString(R.string.optimize_fsrs_done, optimizedReviews))
        } else {
            AppMessages.show(ctx.getString(R.string.optimize_fsrs_too_few, mostReviews, FsrsOptimizer.MIN_REVIEWS))
        }
    }

    fun setReminderEnabled(enabled: Boolean) = launchIo {
        ds.setBoolean(KEY_REMINDER_ENABLED, enabled)
        val current = settings.value
        ReviewReminder.update(getApplication(), enabled, current.reminderHour, current.reminderMinute)
    }

    fun setReminderTime(hour: Int, minute: Int) = launchIo {
        ds.setLong(KEY_REMINDER_MINUTES, (hour * 60 + minute).toLong())
        ReviewReminder.update(getApplication(), settings.value.reminderEnabled, hour, minute)
    }

    // -- Deck writes -------------------------------------------------------

    fun upsertDeck(deck: Deck) = launchIo { repository.upsertDeck(deck) }

    fun addDeck(name: String) = launchIo {
        val s = settings.value
        repository.upsertDeck(
            Deck(
                name = name,
                newPerDay = s.newPerDay,
                maxReviewsPerDay = s.maxReviews,
                desiredRetention = s.desiredRetention,
            ),
        )
    }

    fun deleteDeck(deck: Deck) = launchIo {
        val notes = repository.getNotesByDeck(deck.id)
        val cards = repository.getCardsByDeck(deck.id)
        val logs = repository.getReviewLogsByDeckOrdered(deck.id)
        repository.deleteCardsByDeck(deck.id)
        repository.deleteNotesByDeck(deck.id)
        repository.deleteReviewLogsByDeck(deck.id)
        repository.deleteDeck(deck)
        AppMessages.show(
            getApplication<Application>().getString(R.string.deleted),
            actionLabel = getApplication<Application>().getString(R.string.undo),
            duration = AppMessages.Duration.Long,
        ) {
            launchIo {
                repository.upsertDeck(deck)
                if (notes.isNotEmpty()) repository.upsertNotes(notes)
                if (cards.isNotEmpty()) repository.upsertCards(cards)
                logs.forEach { repository.insertReviewLog(it) }
            }
        }
    }

    fun reorderDecks(decks: List<Deck>) = launchIo { decks.forEach { repository.upsertDeck(it) } }

    // -- Note writes -------------------------------------------------------

    fun saveNote(
        noteId: Long,
        noteTypeId: Long,
        deckId: Long,
        fieldValues: List<String>,
        tags: String,
    ) = launchIo {
        val cfg = noteTypes.value.firstOrNull { it.noteType.id == noteTypeId } ?: return@launchIo
        val flds = fieldValues.joinToString(FIELD_SEPARATOR)
        val sortField = fieldValues.firstOrNull().orEmpty()
        val existing = if (noteId != 0L) repository.getNote(noteId) else null
        val position = existing?.position
            ?: ((repository.getNotesByDeck(deckId).maxOfOrNull { it.position } ?: 0.0) + 1.0)
        val note = Note(
            id = noteId,
            noteTypeId = noteTypeId,
            deckId = deckId,
            guid = existing?.guid ?: randomGuid(),
            flds = flds,
            sortField = sortField,
            tags = tags.trim(),
            mod = nowSeconds(),
            position = position,
        )
        val savedId = repository.upsertNote(note)
        val finalNote = if (noteId == 0L) note.copy(id = savedId) else note
        repository.regenerateCards(finalNote, cfg.noteType, cfg.templates, cfg.fields)
        // Keep generated cards in the note's deck (handles a note being moved decks).
        val misplaced = repository.getCardsByNote(finalNote.id).filter { it.deckId != finalNote.deckId }
        if (misplaced.isNotEmpty()) {
            repository.upsertCards(misplaced.map { it.copy(deckId = finalNote.deckId) })
        }
    }

    fun deleteNote(note: Note) = launchIo {
        val cards = repository.getCardsByNote(note.id)
        val logs = cards.flatMap { repository.getReviewLogsByCard(it.id) }
        repository.deleteCardsByNote(note.id)
        if (cards.isNotEmpty()) repository.deleteReviewLogsByCards(cards.map { it.id })
        repository.deleteNote(note)
        AppMessages.show(
            getApplication<Application>().getString(R.string.deleted),
            actionLabel = getApplication<Application>().getString(R.string.undo),
            duration = AppMessages.Duration.Long,
        ) {
            launchIo {
                repository.upsertNote(note)
                if (cards.isNotEmpty()) repository.upsertCards(cards)
                logs.forEach { repository.insertReviewLog(it) }
            }
        }
    }

    /** Suspends or unsuspends every card of [noteId], and tracks the leech tag. */
    fun setNoteSuspended(noteId: Long, suspended: Boolean) = launchIo {
        repository.setCardsSuspendedByNotes(listOf(noteId), if (suspended) 1 else 0)
    }

    /** Suspends the card currently shown in the review session and advances. */
    fun suspendCurrentCard() = launchIo {
        val s = session ?: return@launchIo
        val card = s.removeCurrent() ?: return@launchIo
        repository.setCardsSuspended(listOf(card.id), 1)
        publishReview()
    }

    fun reorderNotes(notes: List<Note>) = launchIo { repository.upsertNotes(notes) }

    // -- Bulk note operations ---------------------------------------------

    /** Deletes multiple notes (and their cards/logs) with a single undo action. */
    fun deleteNotes(noteIds: List<Long>) = launchIo {
        if (noteIds.isEmpty()) return@launchIo
        val notes = repository.getNotesByIds(noteIds)
        val cards = repository.getCardsByNotes(noteIds)
        val logs = cards.flatMap { repository.getReviewLogsByCard(it.id) }
        repository.deleteCardsByNotes(noteIds)
        if (cards.isNotEmpty()) repository.deleteReviewLogsByCards(cards.map { it.id })
        repository.deleteNotesByIds(noteIds)
        AppMessages.show(
            getApplication<Application>().getString(R.string.deleted),
            actionLabel = getApplication<Application>().getString(R.string.undo),
            duration = AppMessages.Duration.Long,
        ) {
            launchIo {
                repository.upsertNotes(notes)
                if (cards.isNotEmpty()) repository.upsertCards(cards)
                logs.forEach { repository.insertReviewLog(it) }
            }
        }
    }

    /** Moves notes (and their cards) to another deck. */
    fun moveNotes(noteIds: List<Long>, deckId: Long) = launchIo {
        if (noteIds.isEmpty()) return@launchIo
        val notes = repository.getNotesByIds(noteIds)
        var position = repository.getNotesByDeck(deckId).maxOfOrNull { it.position } ?: 0.0
        val moved = notes.map { note ->
            position += 1.0
            note.copy(deckId = deckId, position = position)
        }
        repository.upsertNotes(moved)
        repository.moveCardsByNotes(noteIds, deckId)
    }

    fun addTag(noteIds: List<Long>, tag: String) = launchIo {
        val clean = tag.trim()
        if (clean.isEmpty() || noteIds.isEmpty()) return@launchIo
        val updated = repository.getNotesByIds(noteIds).map { note ->
            val tags = note.tags.split(" ").filter { it.isNotBlank() }.toMutableSet()
            tags.add(clean)
            note.copy(tags = tags.joinToString(" "))
        }
        repository.upsertNotes(updated)
    }

    fun removeTag(noteIds: List<Long>, tag: String) = launchIo {
        val clean = tag.trim()
        if (clean.isEmpty() || noteIds.isEmpty()) return@launchIo
        val updated = repository.getNotesByIds(noteIds).map { note ->
            val tags = note.tags.split(" ").filter { it.isNotBlank() && it != clean }
            note.copy(tags = tags.joinToString(" "))
        }
        repository.upsertNotes(updated)
    }

    fun setNotesSuspended(noteIds: List<Long>, suspended: Boolean) = launchIo {
        if (noteIds.isEmpty()) return@launchIo
        repository.setCardsSuspendedByNotes(noteIds, if (suspended) 1 else 0)
    }

    /** Resets scheduling for every card of the given notes back to new. */
    fun resetSchedulingForNotes(noteIds: List<Long>) = launchIo {
        if (noteIds.isEmpty()) return@launchIo
        val cards = repository.getCardsByNotes(noteIds)
        resetCards(cards)
    }

    /** Resets scheduling for every card in a deck back to new. */
    fun resetSchedulingForDeck(deckId: Long) = launchIo {
        resetCards(repository.getCardsByDeck(deckId))
    }

    private suspend fun resetCards(cards: List<Card>) {
        if (cards.isEmpty()) return
        repository.upsertCards(
            cards.map {
                it.copy(
                    state = CardState.NEW,
                    stability = 0.0,
                    difficulty = 0.0,
                    reps = 0,
                    lapses = 0,
                    dueDate = 0,
                    lastReview = 0,
                )
            },
        )
        repository.deleteReviewLogsByCards(cards.map { it.id })
    }

    /** Exports a deck's notes as `front,back` CSV and shares it. */
    fun exportCsv(deckId: Long) = launchIo {
        val context = getApplication<Application>()
        val notes = repository.getNotesByDeck(deckId).sortedBy { it.position }
        val rows = notes.map { note ->
            val fields = note.fieldList
            stripText(fields.getOrNull(0).orEmpty()) to stripText(fields.getOrNull(1).orEmpty())
        }
        val uri = withContext(Dispatchers.IO) {
            val dir = java.io.File(context.cacheDir, "shared_decks").apply { mkdirs() }
            val deck = repository.getDeck(deckId)
            val safe = (deck?.name ?: "deck").replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = java.io.File(dir, "$safe.csv")
            file.writeText(DeckIo.writeCsv(rows))
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        _shareRequests.emit(uri)
    }

    private fun stripText(md: String): String =
        Regex("<[^>]+>").replace(md, "").replace("\n", " ").trim()

    // -- Note type CRUD ----------------------------------------------------

    fun saveNoteType(
        id: Long,
        name: String,
        css: String,
        type: Int,
        fieldNames: List<String>,
        templates: List<TemplateDraft>,
    ) = launchIo {
        val savedId = repository.upsertNoteType(NoteType(id = id, name = name, type = type, css = css, mod = nowSeconds()))
        val ntId = if (id == 0L) savedId else id

        repository.deleteFieldsByNoteType(ntId)
        repository.upsertNoteTypeFields(
            fieldNames.mapIndexed { ord, fieldName -> com.vayunmathur.flashcards.data.NoteTypeField(noteTypeId = ntId, ord = ord, name = fieldName) },
        )

        repository.deleteTemplatesByNoteType(ntId)
        val effective = if (type == NoteTypeKind.CLOZE) templates.take(1) else templates
        repository.upsertCardTemplates(
            effective.mapIndexed { ord, t ->
                CardTemplate(noteTypeId = ntId, ord = ord, name = t.name, qfmt = t.qfmt, afmt = t.afmt)
            },
        )

        // Regenerate cards for every note of this type against the new templates.
        val nt = repository.getNoteType(ntId) ?: return@launchIo
        val newFields = repository.getFieldsForNoteType(ntId)
        val newTemplates = repository.getTemplatesForNoteType(ntId)
        repository.getNotesByNoteType(ntId).forEach { note ->
            repository.regenerateCards(note, nt, newTemplates, newFields)
        }
    }

    fun deleteNoteType(id: Long) = launchIo {
        if (id in BUILT_IN_NOTE_TYPE_IDS) return@launchIo
        val notes = repository.getNotesByNoteType(id)
        notes.forEach { repository.deleteCardsByNote(it.id) }
        notes.forEach { repository.deleteNote(it) }
        repository.deleteFieldsByNoteType(id)
        repository.deleteTemplatesByNoteType(id)
        repository.getNoteType(id)?.let { repository.deleteNoteType(it) }
    }

    // -- Review session ----------------------------------------------------

    private var session: ReviewSession? = null
    private var lastLogId: Long? = null
    private var sessionNotes: Map<Long, Note> = emptyMap()
    private var sessionDeck: Deck? = null
    private val _review = MutableStateFlow(ReviewUiState())
    val review: StateFlow<ReviewUiState> = _review.asStateFlow()

    fun startSession(
        deckId: Long,
        params: StudyParams = StudyParams(),
        tagFilter: Set<String> = emptySet(),
    ) = launchIo {
        val deck = repository.getDeck(deckId) ?: return@launchIo
        val notes = repository.getNotesByDeck(deckId)
        sessionNotes = notes.associateBy { it.id }
        sessionDeck = deck
        val allCards = repository.getCardsByDeck(deckId)
        val cards = if (tagFilter.isEmpty()) {
            allCards
        } else {
            val allowedNoteIds = notes
                .filter { note -> note.tags.split(" ").any { it in tagFilter } }
                .map { it.id }
                .toSet()
            allCards.filter { it.noteId in allowedNoteIds }
        }
        val now = System.currentTimeMillis()
        session = ReviewSession(
            cards = cards,
            newPerDay = deck.newPerDay,
            maxReviews = deck.maxReviewsPerDay,
            now = now,
            desiredRetention = deck.desiredRetention,
            weights = Scheduler.parseWeights(deck.fsrsWeights),
            params = params,
        )
        lastLogId = null
        publishReview()
    }

    fun gradeCurrent(grade: Grade) = launchIo {
        val s = session ?: return@launchIo
        val original = s.current ?: return@launchIo
        val now = System.currentTimeMillis()
        val prevLastReview = original.lastReview
        val wasReview = original.state == CardState.REVIEW
        val updated = s.grade(grade, now) ?: return@launchIo
        // Cram mode is preview-only: don't persist scheduling or a review log.
        if (s.previewOnly) {
            publishReview()
            return@launchIo
        }
        repository.upsertCard(updated)
        maybeMarkLeech(updated, grade, wasReview)
        val elapsedDays =
            if (prevLastReview > 0) (now - prevLastReview).toDouble() / Scheduler.DAY_MS else 0.0
        val scheduledDays = (updated.dueDate - now).toDouble() / Scheduler.DAY_MS
        lastLogId = repository.insertReviewLog(
            ReviewLog(
                cardId = original.id,
                deckId = original.deckId,
                reviewedAt = now,
                grade = grade.value,
                elapsedDays = elapsedDays,
                scheduledDays = scheduledDays,
                state = updated.state,
            ),
        )
        publishReview()
    }

    /**
     * Auto-suspends a card the first time it reaches `leechThreshold` lapses (Anki's
     * leech behaviour): tags its note, evicts the just-re-queued copy from the
     * in-session queue, and surfaces a message. Only fires on the crossing lapse.
     */
    private suspend fun maybeMarkLeech(updated: Card, grade: Grade, wasReview: Boolean) {
        val threshold = sessionDeck?.leechThreshold ?: 0
        if (threshold <= 0 || grade != Grade.AGAIN || !wasReview) return
        if (updated.lapses != threshold) return
        repository.setCardsSuspended(listOf(updated.id), 1)
        session?.removeFromQueue(updated.id)
        val note = repository.getNote(updated.noteId)
        if (note != null && !note.tags.split(" ").contains(LEECH_TAG)) {
            repository.upsertNote(note.copy(tags = (note.tags + " " + LEECH_TAG).trim()))
        }
        AppMessages.show(getApplication<Application>().getString(R.string.leech_suspended))
    }

    fun undoReview() = launchIo {
        val s = session ?: return@launchIo
        val restored = s.undo() ?: return@launchIo
        repository.upsertCard(restored)
        lastLogId?.let {
            repository.deleteReviewLogById(it)
            lastLogId = null
        }
        publishReview()
    }

    // -- Text-to-speech ----------------------------------------------------

    private var tts: TtsSpeaker? = null

    /** Speaks [text] aloud (markdown/HTML stripped). No-op on blank. */
    fun speak(text: String) {
        val clean = Regex("<[^>]+>").replace(text, "")
            .replace(Regex("""[*_`#>\[\]]"""), "")
            .trim()
        if (clean.isEmpty()) return
        val speaker = tts ?: TtsSpeaker(getApplication()).also { tts = it }
        speaker.speak(clean)
    }

    override fun onCleared() {
        tts?.shutdown()
        super.onCleared()
    }

    private data class RenderedCard(
        val front: String,
        val back: String,
        val typeField: String?,
        val typeAnswer: String?,
    )

    private fun publishReview() {
        val s = session
        _review.value = if (s == null) {
            ReviewUiState(done = true)
        } else {
            val now = System.currentTimeMillis()
            val current = s.current
            val rendered = current?.let { renderCard(it) }
                ?: RenderedCard("", "", null, null)
            ReviewUiState(
                front = rendered.front,
                back = rendered.back,
                remaining = s.remaining,
                done = s.done,
                newCount = s.newCount,
                learningCount = s.learningCount,
                reviewCount = s.reviewCount,
                progress = s.progress,
                intervalLabels = s.previewLabels(now),
                canUndo = s.canUndo,
                typeField = rendered.typeField,
                typeAnswer = rendered.typeAnswer,
                autoPlay = settings.value.autoPlay,
            )
        }
    }

    /** Renders a card's markdown (+ any type-in field) from its note and template. */
    private fun renderCard(card: Card): RenderedCard {
        val note = sessionNotes[card.noteId] ?: return RenderedCard("", "", null, null)
        val cfg = noteTypes.value.firstOrNull { it.noteType.id == note.noteTypeId }
            ?: return RenderedCard(note.sortField, "", null, null)
        val values = note.fieldValues(cfg.fields)
        val template = if (cfg.noteType.type == NoteTypeKind.CLOZE) {
            cfg.templates.firstOrNull()
        } else {
            cfg.templates.firstOrNull { it.ord == card.templateOrd } ?: cfg.templates.firstOrNull()
        } ?: return RenderedCard(note.sortField, "", null, null)
        val clozeOrd = if (cfg.noteType.type == NoteTypeKind.CLOZE) card.templateOrd else null
        val (front, back) = TemplateEngine.render(template.qfmt, template.afmt, values, clozeOrd)
        val typeField = TemplateEngine.typeField(template.qfmt)
        val typeAnswer = typeField?.let { values[it] }
        return RenderedCard(front, back, typeField, typeAnswer)
    }

    // -- Import / export / share ------------------------------------------

    private val _shareRequests = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val shareRequests = _shareRequests.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    /** Exports [deckId] (or the whole collection when null) as a shareable `.apkg`. */
    fun exportApkg(deckId: Long?) = launchIo {
        val context = getApplication<Application>()
        val exportedDecks =
            if (deckId == null) repository.getAllDecks() else listOfNotNull(repository.getDeck(deckId))
        val notes = if (deckId == null) repository.getAllNotes() else repository.getNotesByDeck(deckId)
        val noteIds = notes.map { it.id }.toSet()
        val exportedCards = repository.getAllCards().filter { it.noteId in noteIds }
        val configs = noteTypes.value
        val uri = withContext(Dispatchers.IO) {
            val name = exportedDecks.singleOrNull()?.name ?: "collection"
            val file = ApkgExport.write(context, name, exportedDecks, notes, exportedCards, configs)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        _shareRequests.emit(uri)
    }

    fun importApkg(uri: Uri) = launchIo {
        val context = getApplication<Application>()
        val message = withContext(Dispatchers.IO) {
            runCatching {
                repository.importApkg(context, uri)
            }.getOrElse { it.message ?: "Import failed" }
        }
        _messages.emit(message)
    }

    fun importCsv(deckId: Long, uri: Uri) = launchIo {
        val context = getApplication<Application>()
        val cfg = noteTypes.value.firstOrNull { it.noteType.id == BASIC_NOTE_TYPE_ID } ?: return@launchIo
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } ?: return@launchIo
        val rows = DeckIo.parseCsv(text)
        if (rows.isEmpty()) return@launchIo
        var position = repository.getNotesByDeck(deckId).maxOfOrNull { it.position } ?: 0.0
        rows.forEach { (front, back) ->
            position += 1.0
            val note = Note(
                noteTypeId = BASIC_NOTE_TYPE_ID,
                deckId = deckId,
                guid = randomGuid(),
                flds = listOf(front, back).joinToString(FIELD_SEPARATOR),
                sortField = front,
                mod = nowSeconds(),
                position = position,
            )
            val id = repository.upsertNote(note)
            repository.regenerateCards(note.copy(id = id), cfg.noteType, cfg.templates, cfg.fields)
        }
    }

    // -- Built-in note types ----------------------------------------------

    private suspend fun ensureBuiltInNoteTypes() {
        if (repository.getAllNoteTypes().isNotEmpty()) return
        seedNoteType(
            id = BASIC_NOTE_TYPE_ID,
            name = "Basic",
            type = NoteTypeKind.STANDARD,
            fields = listOf("Front", "Back"),
            templates = listOf(TemplateDraft("Card 1", "{{Front}}", "{{FrontSide}}\n\n---\n\n{{Back}}")),
        )
        seedNoteType(
            id = 2,
            name = "Basic (and reversed card)",
            type = NoteTypeKind.STANDARD,
            fields = listOf("Front", "Back"),
            templates = listOf(
                TemplateDraft("Card 1", "{{Front}}", "{{FrontSide}}\n\n---\n\n{{Back}}"),
                TemplateDraft("Card 2", "{{Back}}", "{{FrontSide}}\n\n---\n\n{{Front}}"),
            ),
        )
        seedNoteType(
            id = 3,
            name = "Cloze",
            type = NoteTypeKind.CLOZE,
            fields = listOf("Text", "Back Extra"),
            templates = listOf(
                TemplateDraft("Cloze", "{{cloze:Text}}", "{{cloze:Text}}\n\n---\n\n{{Back Extra}}"),
            ),
        )
    }

    private suspend fun seedNoteType(
        id: Long,
        name: String,
        type: Int,
        fields: List<String>,
        templates: List<TemplateDraft>,
    ) {
        repository.upsertNoteType(NoteType(id = id, name = name, type = type, mod = nowSeconds()))
        repository.upsertNoteTypeFields(fields.mapIndexed { ord, f -> com.vayunmathur.flashcards.data.NoteTypeField(noteTypeId = id, ord = ord, name = f) })
        repository.upsertCardTemplates(
            templates.mapIndexed { ord, t -> CardTemplate(noteTypeId = id, ord = ord, name = t.name, qfmt = t.qfmt, afmt = t.afmt) },
        )
    }

    private fun launchIo(block: suspend () -> Unit) =
        viewModelScope.launch(Dispatchers.IO) { block() }

    private fun parsePresets(json: String): List<DeckPreset> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DeckPreset(
                    name = o.optString("name"),
                    newPerDay = o.optInt("newPerDay", 20),
                    maxReviews = o.optInt("maxReviews", 200),
                    desiredRetention = o.optDouble("desiredRetention", 0.9),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun presetsToJson(presets: List<DeckPreset>): String {
        val arr = org.json.JSONArray()
        presets.forEach { p ->
            arr.put(
                org.json.JSONObject().apply {
                    put("name", p.name)
                    put("newPerDay", p.newPerDay)
                    put("maxReviews", p.maxReviews)
                    put("desiredRetention", p.desiredRetention)
                },
            )
        }
        return arr.toString()
    }

    companion object {
        const val KEY_RETENTION = "desired_retention"
        const val KEY_NEW_PER_DAY = "new_per_day"
        const val KEY_MAX_REVIEWS = "max_reviews"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_REMINDER_ENABLED = "reminder_enabled"
        const val KEY_REMINDER_MINUTES = "reminder_minutes"
        const val KEY_AUTO_PLAY = "auto_play_audio"
        const val KEY_DECK_PRESETS = "deck_presets"

        const val BASIC_NOTE_TYPE_ID = 1L
        val BUILT_IN_NOTE_TYPE_IDS = setOf(1L, 2L, 3L)

        /** Tag added to a note when one of its cards is auto-suspended as a leech. */
        const val LEECH_TAG = "leech"

        private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

        /** A 16-hex-char random note guid, matching the migration's format. */
        fun randomGuid(): String = (0 until 8).joinToString("") {
            "%02x".format(Random.nextInt(0, 256))
        }

        /** Mastery = fraction of a deck's cards that have graduated to review. */
        fun mastery(cards: List<Card>): Float {
            if (cards.isEmpty()) return 0f
            return cards.count { it.state == CardState.REVIEW }.toFloat() / cards.size
        }
    }
}

/** Factory for constructing [FlashcardsViewModel] with the repository. */
class FlashcardsViewModelFactory(
    private val application: Application,
    private val repository: FlashcardsRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FlashcardsViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return FlashcardsViewModel(application, repository) as T
    }
}
