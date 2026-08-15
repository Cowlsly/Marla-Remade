package com.vayunmathur.files.ui.components

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
) {
    var isDraggingOver by remember { mutableStateOf(false) }
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnStartDrag by rememberUpdatedState(onStartDrag)

    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isDraggingOver) MaterialTheme.colorScheme.primaryContainer
                else if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent
            )
            .dragAndDropSource { _ ->
                val paths = currentOnStartDrag()
                if (paths.isEmpty()) return@dragAndDropSource null

                val uris = try {
                    paths.map { path ->
                        FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", path
                        )
                    }
                } catch (_: Exception) {
                    return@dragAndDropSource null
                }
                val mimeTypes = paths.map { path ->
                    val extension = path.name.substringAfterLast(
                        '.', ""
                    )
                    if (extension == "md") "text/markdown"
                    else MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        extension
                    ) ?: "*/*"
                }.toMutableList().apply {
                    add(
                        ClipDescription.MIMETYPE_TEXT_PLAIN
                    )
                }.distinct().toTypedArray()

                val clipData = ClipData(
                    paths.first().name, mimeTypes, ClipData.Item(
                        paths.first().absolutePath, null, null, uris.first()
                    )
                )
                for (i in 1 until uris.size) {
                    clipData.addItem(
                        ClipData.Item(
                            paths[i].absolutePath, null, null, uris[i]
                        )
                    )
                }

                DragAndDropTransferData(
                    clipData = clipData,
                    flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
                )
            }
            .then(
                if (file.isDirectory && !isReadOnly && file.realFile != null) {
                    Modifier.dragAndDropTarget(shouldStartDragAndDrop = { event ->
                        event.mimeTypes().contains(
                            ClipDescription.MIMETYPE_TEXT_PLAIN
                        )
                    }, target = remember(file.key) {
                        dropTarget(
                            onDragStateChange = { isDraggingOver = it },
                            onDrop = { currentOnMove(it) }
                        )
                    })
                } else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        ListItem(
            content = { Text(file.name.ifEmpty { "/" }) },
            leadingContent = { FileLeading(file, isSelected, 40.dp) },
            supportingContent = {
                if (!file.isDirectory) {
                    file.size?.let { size -> Text(Formatter.formatShortFileSize(context, size)) }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}


