package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CommonSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    padding: PaddingValues = PaddingValues(16.dp)
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
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
        shape = MaterialTheme.shapes.extraLarge,
        singleLine = true
    )
}
