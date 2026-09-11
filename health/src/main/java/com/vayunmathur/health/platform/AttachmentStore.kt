@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.platform

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.vayunmathur.library.util.IntentHelper
import java.io.File
import kotlin.uuid.Uuid

/**
 * Stores vaccination-card scans as files under `filesDir/medical_attachments`.
 *
 * Modelled on `com.vayunmathur.notes.platform.NoteImageStore`. A
 * [com.vayunmathur.health.data.MedicalAttachment] row references a file by name only: the
 * `content://` Uri the user picked carries a read grant that does not survive a reboot, so keeping
 * it would give a broken attachment the next morning.
 */
object AttachmentStore {

    private const val TAG = "AttachmentStore"
    private const val DIR = "medical_attachments"

    /** What [import] recovered about a file the user picked. */
    data class Imported(
        val fileName: String,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
    )

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    fun fileFor(context: Context, fileName: String): File = File(dir(context), fileName)

    /** A shareable Uri for [fileName], for handing to an external viewer. */
    fun uriFor(context: Context, fileName: String): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        fileFor(context, fileName),
    )

    /** Copies [uri] into the attachments dir, or returns null if it could not be read. */
    fun import(context: Context, uri: Uri, fallbackDisplayName: String): Imported? {
        val mimeType = context.contentResolver.getType(uri) ?: GENERIC_MIME_TYPE
        val displayName = IntentHelper.getFileName(context, uri) ?: fallbackDisplayName
        val fileName = "att_${Uuid.random()}${extensionFor(displayName, mimeType)}"
        val dest = File(dir(context), fileName)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            Imported(fileName, displayName, mimeType, dest.length())
        } catch (e: Exception) {
            Log.e(TAG, "Could not import an attachment", e)
            dest.delete()
            null
        }
    }

    fun delete(context: Context, fileName: String) {
        try {
            fileFor(context, fileName).delete()
        } catch (_: Exception) {
        }
    }

    fun isImage(mimeType: String): Boolean = mimeType.startsWith("image/")

    /**
     * Keeps the original extension where there is one, because the stored file is handed to an
     * external viewer through a `FileProvider` Uri and some viewers sniff the path rather than
     * trusting the MIME type they were given.
     */
    private fun extensionFor(displayName: String, mimeType: String): String {
        val fromName = displayName.substringAfterLast('.', "")
        if (fromName.isNotEmpty() && fromName.length <= 5) return ".${fromName.lowercase()}"
        return when {
            mimeType == "application/pdf" -> ".pdf"
            mimeType == "image/png" -> ".png"
            mimeType.startsWith("image/") -> ".jpg"
            else -> ""
        }
    }

    private const val GENERIC_MIME_TYPE = "application/octet-stream"
}
