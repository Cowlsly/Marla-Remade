package com.vayunmathur.files.util
import android.content.Context
import androidx.work.WorkerParameters
import com.vayunmathur.files.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipWorker(context: Context, params: WorkerParameters) : ProgressNotificationWorker(
    context,
    params,
    channelId = "zip_progress_channel",
    notificationId = 1,
    channelNameRes = R.string.zip_channel_name,
    contentTitleRes = R.string.archiving,
) {

    override suspend fun doWork(): Result {
        val sourcePaths = inputData.getStringArray("source_paths") ?: return Result.failure()
        val destPathString = inputData.getString("dest_path") ?: return Result.failure()
        val destFile = File(destPathString)

        createNotificationChannel()
        setForeground(createForegroundInfo(0))

        return try {
            var totalSize = 0L
            sourcePaths.forEach { totalSize += calculateTotalSize(File(it)) }

            var bytesZipped = 0L

            FileOutputStream(destFile).use { fos ->
                ZipOutputStream(fos).use { zipOut ->
                    sourcePaths.forEach { pathString ->
                        addToZip(File(pathString), "", zipOut) { bytes ->
                            bytesZipped += bytes
                            updateProgress(bytesZipped, totalSize)
                        }
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        } finally {
            cancelNotification()
        }
    }

    private fun calculateTotalSize(file: File): Long {
        if (!file.exists()) return 0L
        return if (file.isDirectory) {
            file.listFiles()?.sumOf { calculateTotalSize(it) } ?: 0L
        } else {
            file.length()
        }
    }

    private fun addToZip(
        file: File,
        base: String,
        zipOutputStream: ZipOutputStream,
        onProgress: (Long) -> Unit
    ) {
        if (!file.exists()) return
        val entryName = if (base.isEmpty()) file.name else "$base/${file.name}"

        if (file.isDirectory) {
            val children = file.listFiles()
            if (children == null || children.isEmpty()) {
                zipOutputStream.putNextEntry(ZipEntry("$entryName/"))
                zipOutputStream.closeEntry()
            } else {
                children.forEach { child ->
                    addToZip(child, entryName, zipOutputStream, onProgress)
                }
            }
        } else {
            zipOutputStream.putNextEntry(ZipEntry(entryName))
            val countingOut = CountingOutputStream(zipOutputStream, onProgress)
            FileInputStream(file).use { input ->
                input.copyTo(countingOut)
            }
            countingOut.flush()
            zipOutputStream.closeEntry()
        }
    }

    private class CountingOutputStream(out: OutputStream, private val onProgress: (Long) -> Unit) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) {
            super.write(b, off, len)
            onProgress(len.toLong())
        }
        override fun write(b: Int) {
            super.write(b)
            onProgress(1)
        }
    }
}
