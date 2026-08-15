package com.vayunmathur.share.platform

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream

/**
 * Persists received files to the user-visible storage location.
 *
 * Strategy:
 *  - On API 29+ (scoped storage), insert into MediaStore.Downloads / MediaStore.Images etc
 *    based on MIME, falling back to a "Share" subdirectory in Downloads.
 *  - On API < 29 or when MediaStore insertion fails, write under
 *    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)/Share which remains
 *    visible via the downloads/file-manager on most devices.
 *
 * The received file bytes are streamed by [com.vayunmathur.share.network.transport.TcpTransport];
 * once a file is completely written to a staging [File], hand it here to promote it
 * to the public location and return its public [android.net.Uri].
 */
object FileSaveHelper {

    fun saveToDownloads(context: Context, stagingFile: File, displayName: String, mimeType: String): File {
        return promoteToPublic(context, stagingFile, displayName, mimeType)
    }

    /**
     * Copy [input] to the public Share directory entry for [displayName].
     * Returns the public file handle; the caller is responsible for closing [input].
     */
    fun saveStreamToDownloads(
        context: Context,
        input: InputStream,
        displayName: String,
        mimeType: String,
    ): File {
        val staging = File(context.cacheDir, "share_incoming/$displayName")
        staging.parentFile?.mkdirs()
        staging.outputStream().use { out -> input.copyTo(out) }
        return promoteToPublic(context, staging, displayName, mimeType)
    }

    private fun promoteToPublic(context: Context, staging: File, displayName: String, mimeType: String): File {
        // Prefer MediaStore on Q+; fallback to app-external dir so file manager can find it.
        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            trySaveViaMediaStore(context, staging, displayName, mimeType)
        } else null
        if (saved != null) {
            // Also delete staging; MediaStore copy is canonical.
            try {
                staging.delete()
            } catch (_: Exception) {
            }
            return saved
        }
        val destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, "Share") }
            ?: File(context.filesDir, "Share")
        destDir.mkdirs()
        val dest = uniqueFile(destDir, displayName)
        try {
            staging.copyTo(dest, overwrite = false)
            staging.delete()
        } catch (_: Exception) {
            // If copy failed, at least expose staging itself.
            return staging
        }
        return dest
    }

    private fun trySaveViaMediaStore(
        context: Context,
        staging: File,
        displayName: String,
        mimeType: String,
    ): File? {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else null ?: return null
        val mime = mimeType.ifBlank { "application/octet-stream" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Share")
            }
        }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                staging.inputStream().use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            // Return a file handle pointing to the MediaStore location's cached path if available.
            // Consumers that need a Uri should query Downloads; we return staging-like path via resolver query.
            // As a convenience, try to resolve the _data column; if unavailable, return staging.
            var path: String? = null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                resolver.query(uri, arrayOf(MediaStore.Downloads.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        path = c.getString(0)
                    }
                }
            }
            if (path != null) File(path!!) else run {
                // Return a sentinel file representing the MediaStore entry.
                File(context.cacheDir, "share_mediastore_uri.txt").also {
                    it.writeText(uri.toString())
                }
            }
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        var i = 1
        while (file.exists()) {
            val dot = name.lastIndexOf('.')
            val base = if (dot >= 0) name.substring(0, dot) else name
            val ext = if (dot >= 0) name.substring(dot) else ""
            file = File(dir, "${base}_$i$ext")
            i++
        }
        return file
    }
}
