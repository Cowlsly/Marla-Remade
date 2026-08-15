package com.vayunmathur.files.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import com.vayunmathur.files.platform.FileBrowserItem
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DirectoryItem(
    file: FileBrowserItem,
    isSelected: Boolean,
    isReadOnly: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onMove: (List<File>) -> Unit,
    onStartDrag: () -> List<File>
) {}
