package com.vayunmathur.musicbrainz.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** One entry in the user's music folder. */
data class DocEntry(
    val documentId: String,
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * Storage Access Framework access to the folder the user nominated as their music library.
 *
 * Raw [DocumentsContract] queries rather than `DocumentFile`: a library scan reads every
 * file in the tree, and `DocumentFile` costs a separate query per attribute per file.
 * Everything here blocks on the content resolver, so callers stay off the main thread.
 */
object SafTree {

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    fun listChildren(context: Context, treeUri: Uri, parentDocumentId: String): List<DocEntry> {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val result = ArrayList<DocEntry>()
        context.contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modCol =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol) ?: continue
                val mime = cursor.getString(mimeCol)
                result.add(
                    DocEntry(
                        documentId = docId,
                        name = name,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol),
                        lastModified = if (cursor.isNull(modCol)) 0L else cursor.getLong(modCol),
                    ),
                )
            }
        }
        return result
    }

    fun rootDocumentId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    /**
     * Every file in the tree, depth first.
     *
     * [onFile] is called as the walk proceeds rather than the whole list being returned,
     * so a large library does not have to be held in memory at once. Directories are
     * followed only as deep as [maxDepth]; a music folder nested deeper than that is
     * almost certainly a symlink loop or a misplaced pick like the storage root.
     */
    fun walkFiles(
        context: Context,
        treeUri: Uri,
        maxDepth: Int = 8,
        onFile: (DocEntry) -> Unit,
    ) {
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootDocumentId(treeUri) to 0)
        while (queue.isNotEmpty()) {
            val (docId, depth) = queue.removeFirst()
            for (entry in listChildren(context, treeUri, docId)) {
                if (entry.isDirectory) {
                    if (depth < maxDepth) queue.add(entry.documentId to depth + 1)
                } else {
                    onFile(entry)
                }
            }
        }
    }

    /** Finds a direct child by name, or creates it as a directory. */
    fun findOrCreateDirectory(context: Context, treeUri: Uri, parentDocumentId: String, name: String): String? {
        val existing = listChildren(context, treeUri, parentDocumentId)
            .firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
        if (existing != null) return existing.documentId
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    /** Resolves (creating as needed) a chain of nested directories under the tree root. */
    fun ensurePath(context: Context, treeUri: Uri, segments: List<String>): String? {
        var current = rootDocumentId(treeUri)
        for (segment in segments) {
            current = findOrCreateDirectory(context, treeUri, current, segment) ?: return null
        }
        return current
    }

    /**
     * Creates a file, replacing any existing one with the same name.
     *
     * The delete-first matters: providers append " (1)" to a clashing name rather than
     * overwriting, so re-downloading a track would otherwise pile up duplicates that the
     * library scan then reports as separate copies.
     */
    fun createFile(
        context: Context,
        treeUri: Uri,
        parentDocumentId: String,
        name: String,
        mimeType: String,
    ): Uri? {
        listChildren(context, treeUri, parentDocumentId)
            .firstOrNull { !it.isDirectory && it.name == name }
            ?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it.uri) } }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        return DocumentsContract.createDocument(context.contentResolver, parentUri, mimeType, name)
    }

    /** Strips the characters document providers reject, so a title can become a filename. */
    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.')
            .take(120)
            .ifEmpty { "Unknown" }
}
