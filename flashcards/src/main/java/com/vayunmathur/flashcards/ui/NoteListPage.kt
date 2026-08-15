package com.vayunmathur.flashcards.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.util.DeckOption
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.flashcards.util.NoteListActions
import com.vayunmathur.flashcards.util.NoteListUiState
import com.vayunmathur.flashcards.util.NoteRow
import com.vayunmathur.flashcards.util.StudyParams
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.IconFolderOpen
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconRestore
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconUpload
import com.vayunmathur.library.ui.IconVisibilityOff
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberReorderableLazyListState
import com.vayunmathur.library.ui.rememberSelectionState
import com.vayunmathur.library.ui.reorderDragHandle
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.parseMarkdown

/** Binds the deck with [deckId] to the stateless [NoteListScreen]. */
@Composable
fun NoteListPage(
    backStack: NavBackStack<Route>,
    viewModel: FlashcardsViewModel,
    deckId: Long,
) {
    val context = LocalContext.current
    val decks by viewModel.decks.collectAsStateWithLifecycle()
    val notes by remember(deckId) { viewModel.notesFor(deckId) }
        .collectAsStateWithLifecycle(emptyList())
    val cards by remember(deckId) { viewModel.cardsFor(deckId) }
        .collectAsStateWithLifecycle(emptyList())

    val deckName = decks.firstOrNull { it.id == deckId }?.name ?: ""
    val now = System.currentTimeMillis()
    val cardsByNote = cards.groupBy { it.noteId }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            val name = queryFileName(context, it).orEmpty()
            if (name.endsWith(".apkg", true) || isZip(context, it)) {
                viewModel.importApkg(it)
            } else {
                viewModel.importCsv(deckId, it)
            }
        }
    }

    LaunchedEffect(viewModel, deckId) {
        viewModel.shareRequests.collect { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.share_deck)),
            )
        }
    }

    val actions = remember(backStack, viewModel, deckId) {
        object : NoteListActions {
            override fun back() { backStack.pop() }
            override fun openNote(id: Long) { backStack.add(Route.NoteEdit(deckId, id)) }
            override fun addNote() { backStack.add(Route.NoteEdit(deckId, 0)) }
            override fun deleteNote(note: Note) { viewModel.deleteNote(note) }
            override fun study(tags: Set<String>) {
                backStack.add(Route.Review(deckId, tags = tags.toList()))
            }
            override fun customStudy(params: StudyParams, tags: Set<String>) {
                backStack.add(
                    Route.Review(
                        deckId = deckId,
                        mode = params.mode.ordinal,
                        count = params.count,
                        daysAhead = params.daysAhead,
                        tags = tags.toList(),
                    ),
                )
            }
            override fun reorder(notes: List<Note>) { viewModel.reorderNotes(notes) }
            override fun openStats() { backStack.add(Route.Stats) }
            override fun share() { viewModel.exportApkg(deckId) }
            override fun exportCsv() { viewModel.exportCsv(deckId) }
            override fun deleteNotes(ids: List<Long>) { viewModel.deleteNotes(ids) }
            override fun moveNotes(ids: List<Long>, deckId: Long) { viewModel.moveNotes(ids, deckId) }
            override fun addTag(ids: List<Long>, tag: String) { viewModel.addTag(ids, tag) }
            override fun removeTag(ids: List<Long>, tag: String) { viewModel.removeTag(ids, tag) }
            override fun setSuspended(ids: List<Long>, suspended: Boolean) {
                viewModel.setNotesSuspended(ids, suspended)
            }
            override fun resetScheduling(ids: List<Long>) { viewModel.resetSchedulingForNotes(ids) }
        }
    }

    val rows = notes.sortedBy { it.position }.map { note ->
        val noteCards = cardsByNote[note.id].orEmpty()
        NoteRow(
            note = note,
            cardCount = noteCards.size,
            suspended = noteCards.isNotEmpty() && noteCards.all { it.isSuspended },
        )
    }
    val allTags = notes
        .flatMap { it.tags.split(" ") }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

    NoteListScreen(
        state = NoteListUiState(
            deckName = deckName,
            notes = rows,
            dueCount = cards.count { !it.isSuspended && ((!it.isNew && it.dueDate <= now) || it.isNew) },
            tags = allTags,
            decks = decks.filter { it.id != deckId }.map { DeckOption(it.id, it.name) },
        ),
        actions = actions,
        onImport = {
            importLauncher.launch(
                arrayOf(
                    "application/octet-stream",
                    "application/zip",
                    "text/csv",
                    "text/comma-separated-values",
                    "text/plain",
                    "*/*",
                ),
            )
        },
    )
}

/**
 * The note list for one deck, with no dependency on the ViewModel or the back stack so it
 * can be rendered from a `@Preview` — see `src/screenshotTest`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteListScreen(
    state: NoteListUiState,
    actions: NoteListActions,
    onImport: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var selectedTags by remember { mutableStateOf(emptySet<String>()) }
    val selection = rememberSelectionState<Long>()
    var showCustomStudy by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf<TagDialogKind?>(null) }
    var showReset by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }

    val byQuery = if (query.isBlank()) {
        state.notes
    } else {
        state.notes.filter {
            it.note.flds.contains(query, true) || it.note.tags.contains(query, true)
        }
    }
    val filtered = if (selectedTags.isEmpty()) {
        byQuery
    } else {
        byQuery.filter { row ->
            val tags = row.note.tags.split(" ").toSet()
            selectedTags.all { it in tags }
        }
    }
    val plainList = query.isNotBlank() || selectedTags.isNotEmpty() || selection.isActive

    AppScaffold(
        title = if (selection.isActive) {
            stringResource(R.string.selected_count, selection.count)
        } else {
            state.deckName
        },
        onNavigateBack = { if (selection.isActive) selection.clear() else actions.back() },
        actions = {
            if (selection.isActive) {
                val ids = { selection.selected.toList() }
                IconButton(onClick = { showMove = true }) { IconFolderOpen() }
                IconButton(onClick = { actions.setSuspended(ids(), true); selection.clear() }) {
                    IconVisibilityOff()
                }
                IconButton(onClick = { actions.deleteNotes(ids()); selection.clear() }) { IconDelete() }
                IconButton(onClick = { overflow = true }) { IconMoreVert() }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_tag)) },
                        onClick = { overflow = false; tagDialog = TagDialogKind.ADD },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_tag)) },
                        onClick = { overflow = false; tagDialog = TagDialogKind.REMOVE },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.unsuspend)) },
                        onClick = {
                            overflow = false
                            actions.setSuspended(selection.selected.toList(), false)
                            selection.clear()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reset_scheduling)) },
                        onClick = { overflow = false; showReset = true },
                    )
                }
            } else {
                IconButton(onClick = onImport) { IconUpload() }
                IconButton(onClick = { actions.share() }) { IconShare() }
                IconButton(onClick = { overflow = true }) { IconMoreVert() }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.custom_study)) },
                        onClick = { overflow = false; showCustomStudy = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_csv)) },
                        onClick = { overflow = false; actions.exportCsv() },
                    )
                }
            }
        },
        floatingActionButton = {
            if (!selection.isActive) {
                FloatingActionButton(onClick = { actions.addNote() }) { IconAdd() }
            }
        },
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            if (state.dueCount > 0 && !selection.isActive) {
                Button(
                    onClick = { actions.study(selectedTags) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.study))
                }
            }
            if (state.tags.isNotEmpty()) {
                TagFilterRow(
                    tags = state.tags,
                    selected = selectedTags,
                    onToggle = { tag ->
                        selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag
                    },
                )
            }
            if (state.notes.isNotEmpty()) {
                CommonSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.search_cards),
                    padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                state.notes.isEmpty() -> EmptyState(
                    title = stringResource(R.string.no_cards),
                    message = stringResource(R.string.no_cards_hint),
                    icon = { IconAdd() },
                    modifier = Modifier.fillMaxSize(),
                )
                !plainList -> ReorderableNoteList(filtered, actions, selection)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.note.id }) { row ->
                        NoteRowItem(
                            row = row,
                            selection = selection,
                            onOpen = {
                                if (selection.isActive) selection.toggle(row.note.id)
                                else actions.openNote(row.note.id)
                            },
                            onLongPress = { selection.toggle(row.note.id) },
                        )
                    }
                }
            }
        }
    }

    if (showCustomStudy) {
        CustomStudyDialog(
            onStart = { params ->
                showCustomStudy = false
                actions.customStudy(params, selectedTags)
            },
            onDismiss = { showCustomStudy = false },
        )
    }

    if (showMove) {
        MoveDeckDialog(
            decks = state.decks,
            onPick = { deckId ->
                showMove = false
                actions.moveNotes(selection.selected.toList(), deckId)
                selection.clear()
            },
            onDismiss = { showMove = false },
        )
    }

    tagDialog?.let { kind ->
        TagDialog(
            title = stringResource(if (kind == TagDialogKind.ADD) R.string.add_tag else R.string.remove_tag),
            onConfirm = { tag ->
                val ids = selection.selected.toList()
                if (kind == TagDialogKind.ADD) actions.addTag(ids, tag) else actions.removeTag(ids, tag)
                tagDialog = null
                selection.clear()
            },
            onDismiss = { tagDialog = null },
        )
    }

    if (showReset) {
        ConfirmDialog(
            title = stringResource(R.string.reset_scheduling),
            message = stringResource(R.string.reset_scheduling_message),
            confirmLabel = stringResource(R.string.reset_scheduling),
            dismissLabel = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                actions.resetScheduling(selection.selected.toList())
                showReset = false
                selection.clear()
            },
            onDismiss = { showReset = false },
        )
    }
}

private enum class TagDialogKind { ADD, REMOVE }

@Composable
private fun TagFilterRow(
    tags: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            FilterChip(
                selected = tag in selected,
                onClick = { onToggle(tag) },
                label = { Text(tag) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReorderableNoteList(
    rows: List<NoteRow>,
    actions: NoteListActions,
    selection: com.vayunmathur.library.ui.SelectionState<Long>,
) {
    val listState = rememberLazyListState()
    var local by remember { mutableStateOf(rows) }
    var hasDragged by remember { mutableStateOf(false) }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index in local.indices && to.index in local.indices) {
            local = local.toMutableList().apply { add(to.index, removeAt(from.index)) }
            hasDragged = true
        }
    }
    LaunchedEffect(rows) { if (!reorderState.isAnyItemDragging) local = rows }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && hasDragged) {
            actions.reorder(local.mapIndexed { index, r -> r.note.withPosition(index.toDouble()) })
            hasDragged = false
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(local, key = { it.note.id }) { row ->
            val dragging = reorderState.draggingKey == row.note.id
            val itemModifier = if (dragging) {
                Modifier.zIndex(1f).graphicsLayer { translationY = reorderState.draggingItemTranslation }
            } else {
                Modifier.animateItem()
            }
            ReorderableItem(reorderState, key = row.note.id, modifier = itemModifier) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "noteElevation")
                Surface(shadowElevation = elevation) {
                    NoteRowItem(
                        row = row,
                        selection = selection,
                        onOpen = { actions.openNote(row.note.id) },
                        onLongPress = { selection.toggle(row.note.id) },
                        dragHandle = Modifier.reorderDragHandle(reorderState, key = row.note.id),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRowItem(
    row: NoteRow,
    selection: com.vayunmathur.library.ui.SelectionState<Long>,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    dragHandle: Modifier? = null,
) {
    val fields = row.note.fieldList
    val muted = row.suspended
    ListItem(
        leadingContent = if (selection.isActive) {
            {
                Checkbox(
                    checked = selection.isSelected(row.note.id),
                    onCheckedChange = { selection.toggle(row.note.id) },
                )
            }
        } else {
            null
        },
        headlineContent = {
            Text(parseMarkdown(row.note.sortField.substringBefore('\n').take(60), showMarkers = false))
        },
        supportingContent = {
            Text(parseMarkdown(fields.getOrNull(1).orEmpty().substringBefore('\n').take(60), showMarkers = false))
        },
        trailingContent = {
            Row {
                if (muted) {
                    Text(
                        stringResource(R.string.suspended_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (row.cardCount != 1) {
                    Text(stringResource(R.string.card_count_badge, row.cardCount))
                }
                if (!selection.isActive) {
                    if (dragHandle != null) {
                        IconButton(onClick = {}, modifier = dragHandle) { IconDragHandle() }
                    }
                }
            }
        },
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    )
}

@Composable
private fun CustomStudyDialog(
    onStart: (StudyParams) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(com.vayunmathur.flashcards.util.StudyMode.AHEAD) }
    var count by remember { mutableStateOf("20") }
    var days by remember { mutableStateOf("3") }
    com.vayunmathur.library.ui.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_study)) },
        text = {
            Column {
                ModeRow(R.string.custom_study_ahead, com.vayunmathur.flashcards.util.StudyMode.AHEAD, mode) { mode = it }
                ModeRow(R.string.custom_study_lapses, com.vayunmathur.flashcards.util.StudyMode.LAPSES, mode) { mode = it }
                ModeRow(R.string.custom_study_new, com.vayunmathur.flashcards.util.StudyMode.NEW_ONLY, mode) { mode = it }
                ModeRow(R.string.custom_study_cram, com.vayunmathur.flashcards.util.StudyMode.CRAM, mode) { mode = it }
                com.vayunmathur.library.ui.OutlinedTextField(
                    value = count,
                    onValueChange = { count = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.custom_study_count)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (mode == com.vayunmathur.flashcards.util.StudyMode.AHEAD) {
                    com.vayunmathur.library.ui.OutlinedTextField(
                        value = days,
                        onValueChange = { days = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.custom_study_days)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            com.vayunmathur.library.ui.TextButton(onClick = {
                onStart(
                    StudyParams(
                        mode = mode,
                        count = count.toIntOrNull()?.coerceAtLeast(1) ?: 20,
                        daysAhead = days.toIntOrNull()?.coerceAtLeast(1) ?: 3,
                    ),
                )
            }) { Text(stringResource(R.string.start)) }
        },
        dismissButton = {
            com.vayunmathur.library.ui.TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ModeRow(
    labelRes: Int,
    value: com.vayunmathur.flashcards.util.StudyMode,
    selected: com.vayunmathur.flashcards.util.StudyMode,
    onSelect: (com.vayunmathur.flashcards.util.StudyMode) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        com.vayunmathur.library.ui.RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(stringResource(labelRes), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun MoveDeckDialog(
    decks: List<DeckOption>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    com.vayunmathur.library.ui.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_deck)) },
        text = {
            Column {
                decks.forEach { deck ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { deck.id?.let(onPick) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(deck.name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            com.vayunmathur.library.ui.TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun TagDialog(
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tag by remember { mutableStateOf("") }
    com.vayunmathur.library.ui.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            com.vayunmathur.library.ui.OutlinedTextField(
                value = tag,
                onValueChange = { tag = it },
                label = { Text(stringResource(R.string.tag_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            com.vayunmathur.library.ui.TextButton(onClick = { if (tag.isNotBlank()) onConfirm(tag.trim()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            com.vayunmathur.library.ui.TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun queryFileName(context: android.content.Context, uri: android.net.Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }

private fun isZip(context: android.content.Context, uri: android.net.Uri): Boolean =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(2)
            input.read(header) == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        } ?: false
    }.getOrDefault(false)
