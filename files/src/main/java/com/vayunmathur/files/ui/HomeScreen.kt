package com.vayunmathur.files.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FileCategory
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.HomeUiState
import com.vayunmathur.files.platform.StorageInfo
import com.vayunmathur.library.ui.*
import java.io.File
@Composable
fun HomeScreen(
    home: HomeUiState,
    actions: FilesActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onOpenDrawer: () -> Unit = {},
) {
    val context = LocalContext.current
    LazyListScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onOpenDrawer) { IconMenu() } },
                title = { Text(stringResource(R.string.app_name)) },
            )
        },
        scrollBehavior = appBarScrollBehavior(),
    ) {
        home.storage?.let { s -> item { StorageCard(s) } }

        item {
            HomeRow(
                leading = { IconFolder(tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.internal_storage),
                subtitle = home.storage?.let { Formatter.formatShortFileSize(context, it.totalBytes) },
                onClick = { actions.openInternalStorage() },
            )
        }

        item { HomeSectionHeader(stringResource(R.string.categories)) }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CategoryTile(stringResource(R.string.cat_images), { IconImage(tint = COLOR_IMAGE) }) { actions.openCategory(FileCategory.IMAGES) }
                CategoryTile(stringResource(R.string.cat_videos), { IconVideoCamera(tint = COLOR_VIDEO) }) { actions.openCategory(FileCategory.VIDEOS) }
                CategoryTile(stringResource(R.string.cat_audio), { IconLibraryMusic(tint = COLOR_AUDIO) }) { actions.openCategory(FileCategory.AUDIO) }
                CategoryTile(stringResource(R.string.cat_documents), { IconDescription(tint = COLOR_DOC) }) { actions.openCategory(FileCategory.DOCUMENTS) }
                CategoryTile(stringResource(R.string.cat_downloads), { IconDownload(tint = COLOR_APK) }) { actions.openCategory(FileCategory.DOWNLOADS) }
            }
        }

        if (home.bookmarks.isNotEmpty()) {
            item { HomeSectionHeader(stringResource(R.string.bookmarks)) }
            items(home.bookmarks, key = { "bm:" + it.key }) { bm ->
                HomeRow(
                    leading = { IconFolder(tint = MaterialTheme.colorScheme.outline) },
                    title = bm.name,
                    subtitle = null,
                    onClick = { bm.realFile?.let { actions.openBookmark(it) } },
                    trailing = {
                        IconButton(onClick = { bm.realFile?.let { actions.removeBookmark(it) } }) { IconClose() }
                    },
                )
            }
        }

        if (home.recents.isNotEmpty()) {
            item { HomeSectionHeader(stringResource(R.string.recent_files)) }
            items(home.recents, key = { "rc:" + it.key }) { r ->
                HomeRow(
                    leading = { FileLeading(r, false, 40.dp) },
                    title = r.name,
                    subtitle = r.size?.let { Formatter.formatShortFileSize(context, it) },
                    onClick = {
                        if (r.name.endsWith(".apk", ignoreCase = true) && r.realFile != null) {
                            actions.installApk(r)
                        } else {
                            actions.openFile(r)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StorageCard(storage: StorageInfo) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.internal_storage), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(10.dp))
            val fraction = if (storage.totalBytes > 0) {
                (storage.usedBytes.toFloat() / storage.totalBytes).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(10.dp))
            Text(
                stringResource(
                    R.string.storage_usage,
                    Formatter.formatShortFileSize(context, storage.usedBytes),
                    Formatter.formatShortFileSize(context, storage.totalBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CategoryTile(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HomeRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun HomeSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}


