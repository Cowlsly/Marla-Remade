package com.vayunmathur.communicate.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.communicate.R
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * Compose a poll: a question and at least two options.
 *
 * Options are a mutable list rather than a fixed pair because both Signal and WhatsApp accept an arbitrary
 * number. Blank rows are dropped on submit rather than validated as they are typed, so adding a row you then
 * change your mind about is not an error state.
 */
@Composable
fun PollComposerDialog(
    onDismiss: () -> Unit,
    onCreate: (question: String, options: List<String>) -> Unit,
) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    val usable = question.isNotBlank() && options.count { it.isNotBlank() } >= MIN_OPTIONS

    com.vayunmathur.library.ui.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.poll_new)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text(stringResource(R.string.poll_question)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.md))
                options.forEachIndexed { index, value ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = value,
                            onValueChange = { options[index] = it },
                            label = { Text(stringResource(R.string.poll_option, index + 1)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        // Never below two: a poll with one choice is not a poll.
                        if (options.size > MIN_OPTIONS) {
                            IconButton(onClick = { options.removeAt(index) }) { IconClose() }
                        }
                    }
                }
                if (options.size < MAX_OPTIONS) {
                    TextButton(onClick = { options.add("") }) {
                        Text(stringResource(R.string.poll_add_option))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = usable,
                onClick = {
                    onCreate(question.trim(), options.map { it.trim() }.filter { it.isNotEmpty() })
                    onDismiss()
                },
            ) { Text(stringResource(R.string.poll_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.vayunmathur.library.ui.R.string.cancel))
            }
        },
    )
}

private const val MIN_OPTIONS = 2
private const val MAX_OPTIONS = 10
