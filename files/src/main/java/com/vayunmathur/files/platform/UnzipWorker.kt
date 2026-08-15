package com.vayunmathur.files.util
import android.content.Context
import androidx.work.WorkerParameters
import com.vayunmathur.files.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.util.zip.ZipInputStream

class UnzipWorker(context: Context, params: WorkerParameters) : ProgressNotificationWorker(
    context,
    params,
    channelId = "unzip_progress_channel",
    notificationId = 2,
    channelNameRes = R.string.unzip_channel_name,
    contentTitleRes = R.string.unzipping,
) {

    override suspend fun doWork(): Result {
        val zipPathString = inputData.getString("zip_path") ?: return Result.failure()
        val destPathString = inputData.getString("dest_path") ?: return Result.failure()

        val zipFile = File(zipPathString)
        val destDir = File(destPathString)
        val destDirCanonical = destDir.canonicalFile

        createNotificationChannel()
        setForeground(createForegroundInfo(0))

        return try {
            val zipFileSize = zipFile.length()
            var totalBytesRead = 0L

            FileInputStream(zipFile).use { fis ->
                val countingInputStream = object : FilterInputStream(fis) {
                    override fun read(): Int {
                        val b = super.read()
                        if (b != -1) {
                            totalBytesRead++
                            updateProgress(totalBytesRead, zipFileSize)
                        }
                        return b
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val count = super.read(b, off, len)
                        if (count != -1) {
                            totalBytesRead += count
                            updateProgress(totalBytesRead, zipFileSize)
                        }
                        return count
                    }
                }

                ZipInputStream(countingInputStream).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        val entryFile = File(destDir, entry.name).canonicalFile
                        if (!entryFile.path.startsWith(destDirCanonical.path)) {
                            entry = zipInputStream.nextEntry
                            continue
                        }
                        if (entry.isDirectory) {
                            entryFile.mkdirs()
                        } else {
                            entryFile.parentFile?.mkdirs()
                            FileOutputStream(entryFile).use { out ->
                                zipInputStream.copyTo(out)
                            }
                        }
                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
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
}
