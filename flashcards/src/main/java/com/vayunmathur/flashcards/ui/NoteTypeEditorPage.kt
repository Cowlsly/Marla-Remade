package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.data.NoteTypeKind
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.flashcards.util.NoteTypeEditActions
import com.vayunmathur.flashcards.util.NoteTypeEditUiState
import com.vayunmathur.flashcards.util.NoteTypeListActions
import com.vayunmathur.flashcards.util.NoteTypeListUiState
import com.vayunmathur.flashcards.util.NoteTypeSummary
import com.vayunmathur.flashcards.util.TemplateDraft
import com.vayunmathur.flashcards.util.TemplateEngine
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack

// -------

// ---------------------------------------------------------------------------
// Note type list
// ---------------------------------------------------------------------------

/** Binds the note-type manager list. */
@Composable
fun NoteTypeListPage(backStack: NavBackStack<Route>, viewModel: FlashcardsViewModel) {
    val noteTypes by viewModel.noteTypes.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val countByType = notes.groupingBy { it.noteTypeId }.eachCount()

    val actions = remember(backStack, viewModel) {
        object : NoteTypeListActions {
            override fun back() { backStack.pop() }
            override fun openNoteType(id: Long) { backStack.add(Route.NoteTypeEdit(id)) }
            override fun addNoteType() { backStack.add(Route.NoteTypeEdit(0)) }
            override fun deleteNoteType(id: Long) { viewModel.deleteNoteType(id) }
        }
    }

    NoteTypeListScreen(
        state = NoteTypeListUiState(
            noteTypes = noteTypes.map { cfg ->
                NoteTypeSummary(
                    id = cfg.noteType.id,
                    name = cfg.noteType.name,
                    fieldCount = cfg.fields.size,
                    templateCount = cfg.templates.size,
                    noteCount = countByType[cfg.noteType.id] ?: 0,
                    isCloze = cfg.noteType.type == NoteTypeKind.CLOZE,
                )
            },
        ),
        actions = actions,
    )
}

@Composable
fun NoteTypeListScreen(state: NoteTypeListUiState, actions: NoteTypeListActions) {
    var pendingDelete by remember { mutableStateOf<NoteTypeSummary?>(null) }
    AppScaffold(
        title = stringResource(R.string.manage_note_types),
        onNavigateBack = { actions.back() },
        floatingActionButton = {
            FloatingActionButton(onClick = { actions.addNoteType() }) { IconAdd() }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { paddingValues ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = paddingValues) {
            items(state.noteTypes, key = { it.id }) { summary ->
                val builtIn = summary.id in FlashcardsViewModel.BUILT_IN_NOTE_TYPE_IDS
                ListItem(
                    headlineContent = { Text(summary.name) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.note_type_summary,
                                summary.fieldCount,
                                summary.templateCount,
                                summary.noteCount,
                            ),
                        )
                    },
                    trailingContent = {
                        if (!builtIn) {
                            IconButton(onClick = { pendingDelete = summary }) { IconDelete() }
                        }
                    },
                    modifier = Modifier.clickable { actions.openNoteType(summary.id) },
                )
            }
        }
    }

    pendingDelete?.let { summary ->
        ConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.delete_note_type_message, summary.name, summary.noteCount),
            confirmLabel = stringResource(R.string.delete),
            dismissLabel = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                actions.deleteNoteType(summary.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

// ---------------------------------------------------------------------------
// Note type editor
// ---------------------------------------------------------------------------

/** Binds the editor for the note type with [noteTypeId] (0 for a new one). */
@Composable
fun NoteTypeEditPage(backStack: NavBackStack<Route>, viewModel: FlashcardsViewModel, noteTypeId: Long) {
    val noteTypes by viewModel.noteTypes.collectAsStateWithLifecycle()
    val cfg = noteTypes.firstOrNull { it.noteType.id == noteTypeId }
    val isNew = noteTypeId == 0L

    val actions = remember(backStack, viewModel, noteTypeId) {
        object : NoteTypeEditActions {
            override fun back() { backStack.pop() }
            override fun save(name: String, css: String, type: Int, fields: List<String>, templates: List<TemplateDraft>) {
                viewModel.saveNoteType(noteTypeId, name, css, type, fields, templates)
                backStack.pop()
            }
            override fun delete() {
                viewModel.deleteNoteType(noteTypeId)
                backStack.pop()
            }
        }
    }

    NoteTypeEditScreen(
        state = NoteTypeEditUiState(
            id = noteTypeId,
            name = cfg?.noteType?.name.orEmpty(),
            css = cfg?.noteType?.css.orEmpty(),
            type = cfg?.noteType?.type ?: NoteTypeKind.STANDARD,
            fields = cfg?.fields?.map { it.name } ?: listOf("Front", "Back"),
            templates = cfg?.templates?.map { TemplateDraft(it.name, it.qfmt, it.afmt) }
                ?: listOf(TemplateDraft("Card 1", "{{Front}}", "{{FrontSide}}\n\n---\n\n{{Back}}")),
            isNew = isNew,
        ),
        actions = actions,
    )
}

@Composable
fun NoteTypeEditScreen(state: NoteTypeEditUiState, actions: NoteTypeEditActions) {
    var name by remember(state) { mutableStateOf(state.name) }
    var css by remember(state) { mutableStateOf(state.css) }
    var type by remember(state) { mutableIntStateOf(state.type) }
    val fields = remember(state) { mutableStateListOf(*state.fields.toTypedArray()) }
    val templates = remember(state) { mutableStateListOf(*state.templates.toTypedArray()) }
    val builtIn = state.id in FlashcardsViewModel.BUILT_IN_NOTE_TYPE_IDS

    AppScaffold(
        title = if (state.isNew) stringResource(R.string.new_note_type) else stringResource(R.string.edit_note_type),
        onNavigateBack = { actions.back() },
        actions = {
            IconButton(onClick = {
                actions.save(name.ifBlank { "Note type" }, css, type, fields.toList(), templates.toList())
            }) { com.vayunmathur.library.ui.IconSave() }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = type == NoteTypeKind.STANDARD,
                    onClick = { type = NoteTypeKind.STANDARD },
                    label = { Text(stringResource(R.string.note_type_standard)) },
                    enabled = !builtIn,
                )
                FilterChip(
                    selected = type == NoteTypeKind.CLOZE,
                    onClick = {
                        type = NoteTypeKind.CLOZE
                        while (templates.size > 1) templates.removeAt(templates.lastIndex)
                    },
                    label = { Text(stringResource(R.string.note_type_cloze)) },
                    enabled = !builtIn,
                )
            }

            // Fields
            Text(
                stringResource(R.string.fields),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            fields.forEachIndexed { index, fieldName ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = fieldName,
                        onValueChange = { fields[index] = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { if (index > 0) fields.add(index - 1, fields.removeAt(index)) }) {
                        com.vayunmathur.library.ui.IconKeyboardArrowUp()
                    }
                    IconButton(onClick = { if (fields.size > 1) fields.removeAt(index) }) { IconDelete() }
                }
            }
            OutlinedButton(onClick = { fields.add("Field ${fields.size + 1}") }, modifier = Modifier.padding(top = 4.dp)) {
                IconAdd(); Text(stringResource(R.string.add_field))
            }

            // Templates
            Text(
                stringResource(R.string.templates),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            templates.forEachIndexed { index, draft ->
                TemplateEditor(
                    draft = draft,
                    canDelete = templates.size > 1 && type != NoteTypeKind.CLOZE,
                    onChange = { templates[index] = it },
                    onDelete = { templates.removeAt(index) },
                    fields = fields.toList(),
                    isCloze = type == NoteTypeKind.CLOZE,
                )
            }
            if (type != NoteTypeKind.CLOZE) {
                OutlinedButton(
                    onClick = { templates.add(TemplateDraft("Card ${templates.size + 1}", "{{Front}}", "{{Back}}")) },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    IconAdd(); Text(stringResource(R.string.add_template))
                }
            }

            if (!state.isNew && !builtIn) {
                OutlinedButton(onClick = { actions.delete() }, modifier = Modifier.padding(top = 24.dp)) {
                    IconDelete(); Text(stringResource(R.string.delete))
                }
            }
        }
    }
}

@Composable
private fun TemplateEditor(
    draft: TemplateDraft,
    canDelete: Boolean,
    onChange: (TemplateDraft) -> Unit,
    onDelete: () -> Unit,
    fields: List<String>,
    isCloze: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onChange(draft.copy(name = it)) },
                    label = { Text(stringResource(R.string.template_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (canDelete) {
                    IconButton(onClick = onDelete) { IconDelete() }
                }
            }
            OutlinedTextField(
                value = draft.qfmt,
                onValueChange = { onChange(draft.copy(qfmt = it)) },
                label = { Text(stringResource(R.string.template_front)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = draft.afmt,
                onValueChange = { onChange(draft.copy(afmt = it)) },
                label = { Text(stringResource(R.string.template_back)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            TemplatePreview(draft, fields, isCloze)
        }
    }
}

@Composable
private fun TemplatePreview(draft: TemplateDraft, fields: List<String>, isCloze: Boolean) {
    val sample = if (isCloze) {
        fields.associateWith { name ->
            if (name.equals("Text", true) || fields.firstOrNull() == name) "The {{c1::answer}} here" else name
        }
    } else {
        fields.associateWith { it }
    }
    val (front, back) = TemplateEngine.render(
        draft.qfmt,
        draft.afmt,
        sample,
        clozeOrd = if (isCloze) 0 else null,
    )
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.preview), style = MaterialTheme.typography.labelMedium)
        MarkdownContent(text = front, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        MarkdownContent(text = back, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
    }
}
