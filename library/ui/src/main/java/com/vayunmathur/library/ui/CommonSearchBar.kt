@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

/**
 * The app-facing inline search field: an always-visible local filter, not an
 * expandable global-search bar.
 *
 * This is deliberately built on [SearchBarInputField] (M3
 * `SearchBarDefaults.InputField`) rather than the full [SearchBar] (or the newer
 * `SearchBarState` family): every call-site filters an in-memory list live and has
 * no overlay content, so an expand-to-fullscreen bar would hide the very list being
 * filtered, add an expand/collapse cycle on every focus, and fight the `TopAppBar`
 * title slot it usually sits in — pure regression for zero benefit. The input field
 * alone gives real search semantics (IME search action, search text style/colors,
 * a11y role) with the same inline layout footprint as before.
 */
@Composable
fun CommonSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    padding: PaddingValues = PaddingValues(16.dp)
) {
    val focusManager = LocalFocusManager.current
    SearchBarInputField(
        query = value,
        onQueryChange = onValueChange,
        // Live filtering already applied the query; the IME action just dismisses.
        onSearch = { focusManager.clearFocus() },
        expanded = false,
        onExpandedChange = {},
        modifier = modifier
            .fillMaxWidth()
            .padding(padding),
        placeholder = { Text(placeholder) },
        leadingIcon = {
            IconSearch()
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    IconClose()
                }
            }
        } else null,
    )
}
