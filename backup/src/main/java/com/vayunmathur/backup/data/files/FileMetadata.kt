package com.vayunmathur.backup.data.files

import kotlinx.serialization.Serializable

/**
 * A single backed-up file/media item. [fileId] is a stable hash of the item's
 * location, so re-running a backup overwrites the same blob rather than duplicating.
 */
@Serializable
data class FileEntry(
    val fileId: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val size: Long,
    val dateModified: Long,
    val mediaType: String,
)

/** The manifest of backed-up files, stored (encrypted) at `files/index.json`. */
@Serializable
data class FileIndex(val files: List<FileEntry> = emptyList())
