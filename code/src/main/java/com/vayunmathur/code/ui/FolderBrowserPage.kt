package com.vayunmathur.code.ui

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.R
import com.vayunmathur.code.Route
import com.vayunmathur.code.util.FileFiles
import com.vayunmathur.code.util.EditorViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconFolder
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * In-app folder picker. Android has no "pick a folder → get a path" system API once we work over
 * real [File] paths, so this drills through directories from external storage and lets the user
 * open the current one as the project root. Replaces the old SAF `OpenDocumentTree` launcher.
 */
@Composable
fun FolderBrowserPage(viewModel: EditorViewModel, backStack: NavBackStack<Route>) {
    val storageRoot = remember { Environment.getExternalStorageDirectory() }
    var currentDir by remember { mutableStateOf(storageRoot) }
    var dirs by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(currentDir) {
        dirs = withContext(Dispatchers.IO) {
            FileFiles.listChildren(currentDir).filter { it.isDirectory }.map { it.file }
        }
    }

    AppScaffold(title = stringResource(R.string.open_folder), backStack = backStack, scrollBehavior = appBarScrollBehavior()) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                text = currentDir.absolutePath,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                onClick = {
                    viewModel.openFolder(currentDir)
                    backStack.pop()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text(stringResource(R.string.open_this_folder)) }
            Spacer(Modifier.width(8.dp))
            HorizontalDivider()

            LazyColumn(Modifier.fillMaxSize()) {
                val parent = currentDir.parentFile
                if (parent != null && currentDir.absolutePath != storageRoot.absolutePath) {
                    item(key = "..") {
                        FolderRow(name = "..", onClick = { currentDir = parent })
                    }
                }
                items(dirs, key = { it.absolutePath }) { dir ->
                    FolderRow(name = dir.name, onClick = { currentDir = dir })
                }
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconFolder(Modifier.width(24.dp))
        Text(text = name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconChevronRight(Modifier.width(20.dp))
    }
}
