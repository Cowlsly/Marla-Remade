package com.vayunmathur.library.ui.game

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * A dropdown for choosing one of a small fixed set of game options, styled to sit in an app bar.
 *
 * Games with a handful of modes — a level ladder, a daily board, sometimes a timed mode — put this where
 * a title would go, so the mode and the current level read as one thing and the bar keeps to a single
 * control. It is a dropdown rather than a tab row because the label doubles as the title, and because
 * two or three tabs across the top of a game board is a lot of chrome for a rare action.
 *
 * Also used for in-board pickers such as a difficulty selector, which is the same widget in a smaller
 * [textStyle].
 *
 * [label] renders the *selected* option, so a caller can fold extra context in — "Level 12" for a ladder
 * mode, a bare name for the rest. [menuLabel] is the plain name used inside the menu, where that
 * context would be noise. Both are `@Composable` so they can read string resources.
 *
 * @param options every choice on offer, in the order they should be listed.
 */
@Composable
fun <T> GameModeChooser(
    selected: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    menuLabel: @Composable (T) -> String = label,
    textStyle: TextStyle = MaterialTheme.typography.titleLarge,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = label(selected),
                style = textStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconArrowDropDown()
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in options) {
                SelectableDropdownMenuItem(
                    selected = option == selected,
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    text = { Text(menuLabel(option)) },
                    selectedLeadingIcon = { IconCheck() },
                )
            }
        }
    }
}
