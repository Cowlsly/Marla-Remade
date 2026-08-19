package com.vayunmathur.files.platform
import android.content.Context
import androidx.work.WorkerParameters
import com.vayunmathur.files.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
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
        // The caller checks the archive's declared size against this too, but the declaration comes
        // from the archive and can lie, so the real ceiling is enforced here as bytes are written.
        val budget = inputData.getLong("size_budget", Long.MAX_VALUE)

        val zipFile = File(zipPathString)
        val destDir = File(destPathString)
        val destDirCanonical = destDir.canonicalFile

        createNotificationChannel()
        setForeground(createForegroundInfo(0))

        // Everything this run created, so a rejected archive does not leave its partial output behind.
        val extracted = mutableListOf<File>()

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
                    var totalWritten = 0L
                    var entryCount = 0
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        if (++entryCount > ArchiveLimits.MAX_ENTRIES) throw ArchiveTooLargeException()
                        val entryFile = ArchiveLimits.resolveEntry(destDirCanonical, entry.name)
                        if (entryFile == null) {
                            entry = zipInputStream.nextEntry
                            continue
                        }
                        if (entry.isDirectory) {
                            entryFile.mkdirs()
                        } else {
                            entryFile.parentFile?.mkdirs()
                            extracted.add(entryFile)
                            FileOutputStream(entryFile).use { out ->
                                totalWritten += copyBounded(zipInputStream, out, budget - totalWritten)
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
            extracted.forEach { runCatching { it.delete() } }
            Result.failure()
        } finally {
            cancelNotification()
        }
    }

    /**
     * Copies one entry, refusing to write more than [remaining] bytes.
     *
     * A plain copy would follow the entry to its end, which is exactly what a zip bomb relies on: a
     * few kilobytes of input can ask for gigabytes of output.
     */
    private fun copyBounded(input: InputStream, out: OutputStream, remaining: Long): Long {
        if (remaining <= 0) throw ArchiveTooLargeException()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (written + read > remaining) throw ArchiveTooLargeException()
            out.write(buffer, 0, read)
            written += read
        }
        return written
    }
}
