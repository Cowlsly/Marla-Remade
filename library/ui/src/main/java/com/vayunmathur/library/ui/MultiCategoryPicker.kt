package com.vayunmathur.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * A field for choosing several values from a fixed set: it looks like a read-only text field, its value
 * is the chips inside it, and tapping it opens a menu rather than the keyboard.
 *
 * Built on [OutlinedTextFieldDefaults.DecorationBox] rather than drawn by hand, so the outline, the
 * label and the way the label lifts out of the border are the real thing and stay correct as the theme
 * changes. The chips take the place of the inner text field; [selected] doubles as the field's value so
 * that the label floats exactly when something is chosen.
 *
 * Tapping anywhere on the field opens the menu, via an overlay that covers it. That is the point of the
 * component: a plain read-only text field still raises the IME when focused, which is wrong for a value
 * that cannot be typed.
 */
@Composable
fun <T> MultiCategoryPicker(
    label: String,
    selected: List<T>,
    available: List<T>,
    itemLabel: (T) -> String,
    onAdd: (T) -> Unit,
    onRemove: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** Per-chip modifier, for morphing a chip into its counterpart on another screen. */
    chipModifier: @Composable (T) -> Modifier = { Modifier },
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    Box(modifier.fillMaxWidth()) {
        CategoryChipField(
            label = label,
            selected = selected,
            itemLabel = itemLabel,
            onRemove = onRemove,
            interactionSource = interactionSource,
            trailingIcon = { IconArrowDropDown() },
            chipModifier = chipModifier,
        )

        // Above the chips so the whole field opens the menu, but the chips' own remove taps still win
        // because they are drawn later in the same Box.
        if (available.isNotEmpty()) {
            Box(
                Modifier
                    .matchParentSize()
                    .toggleable(
                        value = expanded,
                        onValueChange = { expanded = it },
                        interactionSource = interactionSource,
                        indication = null,
                    )
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            available.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    onClick = {
                        onAdd(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * The same field, but for values that come from somewhere other than a fixed list.
 *
 * Tapping it calls [onAddClick] instead of opening a menu, so the caller can push a picker screen or
 * a dialog. Everything else — the outline, the floating label, the chips, the remove taps — is
 * identical, which is the point: a value that happens to be produced by a time picker rather than
 * chosen from a set should not look like a different kind of control.
 */
@Composable
fun <T> MultiCategoryPicker(
    label: String,
    selected: List<T>,
    itemLabel: (T) -> String,
    onAddClick: () -> Unit,
    onRemove: (T) -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: @Composable () -> Unit = { IconAdd() },
    chipModifier: @Composable (T) -> Modifier = { Modifier },
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(modifier.fillMaxWidth()) {
        CategoryChipField(
            label = label,
            selected = selected,
            itemLabel = itemLabel,
            onRemove = onRemove,
            interactionSource = interactionSource,
            trailingIcon = trailingIcon,
            chipModifier = chipModifier,
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onAddClick,
                )
        )
    }
}

/** The shared field body: outline, floating label and the chips that stand in for its text. */
@Composable
private fun <T> CategoryChipField(
    label: String,
    selected: List<T>,
    itemLabel: (T) -> String,
    onRemove: (T) -> Unit,
    interactionSource: MutableInteractionSource,
    trailingIcon: @Composable () -> Unit,
    chipModifier: @Composable (T) -> Modifier,
) {
    OutlinedTextFieldDefaults.DecorationBox(
        value = selected.joinToString { itemLabel(it) },
        innerTextField = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                selected.forEach { item ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(item) },
                        label = { Text(itemLabel(item)) },
                        trailingIcon = { IconRemoveCircle(modifier = Modifier.size(18.dp)) },
                        modifier = chipModifier(item),
                    )
                }
            }
        },
        enabled = true,
        singleLine = false,
        visualTransformation = VisualTransformation.None,
        interactionSource = interactionSource,
        isError = false,
        label = { Text(label) },
        trailingIcon = trailingIcon,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    )
}
