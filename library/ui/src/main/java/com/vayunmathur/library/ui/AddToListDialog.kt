package com.vayunmathur.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * "Add to …" picker: a titled dialog listing [options] with a checkbox each,
 * plus an optional inline "create new" field, confirmed with an OK button.
 *
 * Music and YouPipe had each written their own add-to-playlist dialog - one an
 * [AlertDialog] with single-select radios and a separate create dialog, the
 * other a hand-built `Dialog`+`Card` with live-toggling checkboxes and an inline
 * field. This is the shared shape: checkboxes staged locally and applied on OK,
 * with the create field built in. It is generic over the item type so anything
 * that means "pick some of these, optionally make a new one" can use it.
 *
 * Selection is staged: [onConfirm] receives the finally-checked set. Callers
 * that track prior membership (a video already in some playlists) diff the
 * result against their own initial state to know what to add and remove.
 *
 * [initiallyChecked] seeds a row's checkbox; staged toggles survive [options]
 * updating (e.g. after a create) rather than resetting.
 */
@Composable
fun <T> AddToListDialog(
    title: String,
    options: List<T>,
    itemLabel: (T) -> String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: (Set<T>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyChecked: (T) -> Boolean = { false },
    itemKey: (T) -> Any = { it as Any },
    createLabel: String? = null,
    onCreate: ((String) -> Unit)? = null,
    canCreate: (String) -> Boolean = { it.isNotBlank() },
) {
    // Keyed by itemKey rather than rebuilt from [options], so an item added
    // mid-dialog gets its initial state while existing staged toggles persist.
    val checked = remember { mutableStateMapOf<Any, Boolean>() }
    fun isChecked(item: T) = checked[itemKey(item)] ?: initiallyChecked(item)

    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title) },
        text = {
            Column {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(options, key = { itemKey(it) }) { item ->
                        val itemChecked = isChecked(item)
                        ListItem(
                            content = { Text(itemLabel(item)) },
                            trailingContent = {
                                Checkbox(
                                    checked = itemChecked,
                                    onCheckedChange = { checked[itemKey(item)] = it },
                                )
                            },
                            modifier = Modifier.clickable { checked[itemKey(item)] = !itemChecked },
                        )
                    }
                }
                if (createLabel != null && onCreate != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text(createLabel) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                onCreate(newName.trim())
                                newName = ""
                            },
                            enabled = canCreate(newName),
                        ) { IconAdd() }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(options.filter { isChecked(it) }.toSet()) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
