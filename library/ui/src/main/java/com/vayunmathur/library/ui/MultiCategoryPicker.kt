package com.vayunmathur.library.ui

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
            trailingIcon = { IconArrowDropDown() },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
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
