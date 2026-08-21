package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconWork
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.R
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.util.SavedPlacesViewModel

/**
 * Saved-places screen (P6): view / edit / remove the user's saved places. Home
 * and Work quick-access slots, the flat starred list (rename, add-to-list,
 * remove), and named lists (create, delete, remove members). Built from the
 * shared `library/ui` settings components (no raw Scaffold); all edits go through
 * [SavedPlacesViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPlacesPage(backStack: NavBackStack<Route>, viewModel: SavedPlacesViewModel) {
    val home by viewModel.home.collectAsState()
    val work by viewModel.work.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val lists by viewModel.lists.collectAsState()

    var renameTarget by remember { mutableStateOf<SavedPlace?>(null) }
    var addToListTarget by remember { mutableStateOf<SavedPlace?>(null) }
    var showCreateList by remember { mutableStateOf(false) }

    AppScaffold(
        title = stringResource(R.string.saved_places_title),
        backStack = backStack,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // --- Quick access: Home / Work ---
            SettingsSection(title = stringResource(R.string.saved_quick_access)) {
                if (home == null && work == null) {
                    SettingsRow(
                        title = stringResource(R.string.saved_quick_access_empty),
                        enabled = false,
                    )
                }
                home?.let { place ->
                    SettingsRow(
                        title = place.name,
                        supportingText = stringResource(R.string.saved_place_home),
                        leadingContent = { IconHome() },
                        trailingContent = {
                            IconButton(onClick = { viewModel.clearHome() }) { IconClose() }
                        },
                    )
                }
                work?.let { place ->
                    SettingsRow(
                        title = place.name,
                        supportingText = stringResource(R.string.saved_place_work),
                        leadingContent = { IconWork() },
                        trailingContent = {
                            IconButton(onClick = { viewModel.clearWork() }) { IconClose() }
                        },
                    )
                }
            }

            // --- Flat saved list ---
            SettingsSection(title = stringResource(R.string.saved_list_header)) {
                if (saved.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.saved_list_empty),
                        enabled = false,
                    )
                }
                saved.forEach { place ->
                    SavedPlaceRow(
                        place = place,
                        onRename = { renameTarget = place },
                        onAddToList = { addToListTarget = place },
                        onRemove = { viewModel.removeSaved(place) },
                    )
                }
            }

            // --- Named lists ---
            SettingsSection(title = stringResource(R.string.saved_lists_header)) {
                SettingsRow(
                    title = stringResource(R.string.saved_lists_new),
                    leadingContent = { IconAdd() },
                    onClick = { showCreateList = true },
                )
                lists.forEach { (name, places) ->
                    SettingsRow(
                        title = name,
                        supportingText = androidx.compose.ui.res.pluralStringResource(
                            R.plurals.saved_list_count, places.size, places.size
                        ),
                        leadingContent = { IconList() },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deleteList(name) }) { IconDelete() }
                        },
                    )
                    places.forEach { place ->
                        SettingsRow(
                            title = place.name,
                            modifier = Modifier.padding(start = 16.dp),
                            leadingContent = { IconStar() },
                            trailingContent = {
                                IconButton(onClick = { viewModel.removeFromList(name, place) }) { IconClose() }
                            },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        TextEntryDialog(
            title = stringResource(R.string.saved_rename_title),
            initial = target.name,
            confirmLabel = stringResource(R.string.saved_rename_confirm),
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.renameSaved(target, newName)
                renameTarget = null
            },
        )
    }

    if (showCreateList) {
        TextEntryDialog(
            title = stringResource(R.string.saved_lists_new),
            initial = "",
            confirmLabel = stringResource(R.string.saved_lists_create),
            onDismiss = { showCreateList = false },
            onConfirm = { name ->
                viewModel.createList(name)
                showCreateList = false
            },
        )
    }

    addToListTarget?.let { target ->
        AddToListDialog(
            lists = lists.keys.toList(),
            onDismiss = { addToListTarget = null },
            onPick = { listName ->
                viewModel.addToList(listName, target)
                addToListTarget = null
            },
            onCreateAndAdd = { listName ->
                viewModel.createList(listName)
                viewModel.addToList(listName, target)
                addToListTarget = null
            },
        )
    }
}

@Composable
private fun SavedPlaceRow(
    place: SavedPlace,
    onRename: () -> Unit,
    onAddToList: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    SettingsRow(
        modifier = modifier,
        title = place.name,
        leadingContent = { IconStar() },
        trailingContent = {
            IconButton(onClick = { menuOpen = true }) { IconMoreVert() }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.saved_action_rename)) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.saved_action_add_to_list)) },
                    onClick = { menuOpen = false; onAddToList() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.saved_action_remove)) },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        },
    )
}

@Composable
private fun TextEntryDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.saved_cancel)) }
        },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(title) },
            )
        },
    )
}

@Composable
private fun AddToListDialog(
    lists: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onCreateAndAdd: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onCreateAndAdd(newName) }, enabled = newName.isNotBlank()) {
                Text(stringResource(R.string.saved_lists_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.saved_cancel)) }
        },
        title = { Text(stringResource(R.string.saved_action_add_to_list)) },
        text = {
            Column {
                lists.forEach { name ->
                    SettingsRow(title = name, leadingContent = { IconList() }, onClick = { onPick(name) })
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.saved_lists_new)) },
                )
            }
        },
    )
}
