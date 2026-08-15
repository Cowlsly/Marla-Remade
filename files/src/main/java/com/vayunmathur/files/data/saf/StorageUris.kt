package com.vayunmathur.files.data.saf

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Maps between real [File]s and the platform ExternalStorageProvider's document IDs, so the SAF
 * picker can hand back the same provider-backed `content://com.android.externalstorage.documents`
 * URIs that DocumentsUI returns. Those URIs are queryable via [DocumentsContract] and support
 * persistable permission grants — unlike `file://` or one-shot FileProvider URIs.
 *
 * A document ID is `"<volumeId>:<relativePath>"`, e.g. `primary:Download/report.pdf`. The primary
 * shared volume uses the id `primary`; other volumes use their StorageVolume UUID (the same id
 * ExternalStorageProvider itself uses).
 */
object StorageUris {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** Document id (e.g. `primary:Pictures/a.jpg`) for [file], or null if it isn't on a known volume. */
    fun documentIdFor(context: Context, file: File): String? {
        val sm = context.getSystemService(StorageManager::class.java) ?: return null
        val path = file.absolutePath
        // Longest matching volume root wins (handles nested mount points deterministically).
        val best = sm.storageVolumes
            .mapNotNull { vol -> vol.directory?.let { it to vol } }
            .filter { (dir, _) -> path == dir.absolutePath || path.startsWith(dir.absolutePath + "/") }
            .maxByOrNull { (dir, _) -> dir.absolutePath.length }
            ?: return null
        val (dir, vol) = best
        val volumeId = if (vol.isPrimary) "primary" else (vol.uuid ?: return null)
        val relative = path.removePrefix(dir.absolutePath).trimStart('/')
        return "$volumeId:$relative"
    }

    /** Provider-backed single-document URI for [file], or null if it isn't on a known volume. */
    fun documentUriFor(context: Context, file: File): Uri? =
        documentIdFor(context, file)?.let {
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, it)
        }

    /** Provider-backed tree URI for directory [dir], or null if it isn't on a known volume. */
    fun treeUriFor(context: Context, dir: File): Uri? =
        documentIdFor(context, dir)?.let {
            DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, it)
        }

    /**
     * Fallback URI for a plain (non-privileged) `GET_CONTENT` response, where we can't grant a
     * provider URI. Returns a FileProvider URI the caller can read via a one-shot grant.
     */
    fun fileProviderUriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun mimeTypeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"

    /**
     * Whether [file] satisfies the caller's requested MIME constraints. [filters] come from the
     * request's `type` plus `EXTRA_MIME_TYPES`; an empty list means "any".
     */
    fun matchesMime(file: File, filters: List<String>): Boolean {
        if (filters.isEmpty() || filters.any { it == "*/*" }) return true
        val mime = mimeTypeOf(file)
        return filters.any { filter -> mimeMatches(filter, mime) }
    }

    private fun mimeMatches(filter: String, mime: String): Boolean {
        if (filter == mime) return true
        val slash = filter.indexOf('/')
        if (slash < 0) return false
        val fType = filter.substring(0, slash)
        val fSub = filter.substring(slash + 1)
        if (fType != "*" && !mime.startsWith("$fType/")) return false
        return fSub == "*" || mime.endsWith("/$fSub")
    }
}
