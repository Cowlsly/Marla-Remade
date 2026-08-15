package com.vayunmathur.email.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.R
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.library.ui.*

@Composable
fun DraftsScreen(
    viewModel: EmailViewModel,
    onBack: () -> Unit,
    onOpenDraft: (Long) -> Unit,
) {
    val drafts by viewModel.drafts.collectAsStateWithLifecycle(emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drafts)) },
                navigationIcon = { IconNavigation(onBack) },
            )
        }
    ) { padding ->
        if (drafts.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.no_drafts),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(drafts, key = { it.id }) { d ->
                    ListItem(
                        content = { Text(d.subject.ifBlank { stringResource(R.string.no_subject) }) },
                        supportingContent = {
                            val prefix = if (d.to.isNotBlank()) "To: ${d.to}  " else ""
                            Text(prefix + d.body.replace("\n", " ").take(80), maxLines = 2)
                        },
                        modifier = Modifier.clickable { onOpenDraft(d.id) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deleteDraft(d.id) }) {
                                com.vayunmathur.library.ui.IconDelete()
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun uriName(context: android.content.Context, uri: android.net.Uri): String =