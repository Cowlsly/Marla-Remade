package com.vayunmathur.translate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.translate.util.Language
import com.vayunmathur.translate.util.Languages

/**
 * A compact language selector: a text button showing the current language's
 * native name plus a dropdown of [options]. The checked item is marked.
 */
@Composable
fun LanguagePicker(
    selectedCode: String,
    options: List<Language>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(
                text = Languages.byCode(selectedCode).nativeName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconArrowDropDown(Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { lang ->
                DropdownMenuItem(
                    text = { Text("${lang.nativeName}  ·  ${lang.englishName}") },
                    onClick = {
                        onSelected(lang.code)
                        expanded = false
                    },
                    trailingIcon = if (lang.code == selectedCode) {
                        { IconCheck(Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
