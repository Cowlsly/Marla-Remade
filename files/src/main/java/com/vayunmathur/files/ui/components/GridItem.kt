package com.vayunmathur.files.ui.components

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridItem(
    file: FileBrowserItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            FileLeading(file, isSelected, 56.dp)
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = file.name.ifEmpty { "/" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}


