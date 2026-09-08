package com.vayunmathur.updater.platform

import android.util.Log
import com.vayunmathur.updater.domain.OtaDownloadPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

private const val TAG = "OtaDownloader"

private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 30_000
private const val BUFFER_SIZE = 1 shl 16

/** How often to report progress upward. A 1-2 GB read would otherwise report thousands of times. */
private const val PROGRESS_INTERVAL_MS = 500L

/** 416 Range Not Satisfiable. `HttpURLConnection` has no constant for it. */
private const val HTTP_RANGE_NOT_SATISFIABLE = 416

/**
 * Fetches an OTA package to local storage.
 *
 * The download is resumable, which is not a nicety at this size: a full package is one to two
 * gigabytes and a transfer that long will be interrupted — the screen locks, the network
 * changes, the process is killed. Bytes land in `<name>.part` and the next attempt continues
 * with a `Range` request, so an interrupted download costs the remainder rather than the whole
 * thing. The Range/206/200 handling here is the same shape as
 * `library/downloadservice`'s `streamToPart`, including treating a 200 in response to a Range
 * request as the server refusing to resume and restarting from zero.
 *
 * Nothing here inspects what it downloaded. The file is untrusted until
 * [OtaInstaller] has run `RecoverySystem.verifyPackage` over it.
 */
object OtaDownloader {

    sealed interface Result {
        data class Downloaded(val file: File, val incremental: Boolean) : Result

        /**
         * The server has no such artifact.
         *
         * Ordinary, not an error: an incremental exists only for one exact source build, so a
         * device that skipped a release will always miss on the first candidate.
         */
        data object NotFound : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * Try each candidate for [targetBuild] in order and return the first that downloads.
     *
     * Only a [Result.NotFound] moves to the next candidate. A real failure stops: an incremental
     * that died on a TLS error or a full disk is not a reason to immediately attempt the 1-2 GB
     * package, which will hit the same wall having spent much more of the user's data getting
     * there. The next scheduled run resumes the incremental from its `.part` instead.
     */
    suspend fun download(
        directory: File,
        device: String,
        currentBuild: String,
        targetBuild: String,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): Result {
        val candidates = OtaDownloadPlan.candidates(device, currentBuild, targetBuild)
        discardStalePackages(directory, candidates)
        for (candidate in candidates) {
            when (val result = fetch(directory, candidate, onProgress)) {
                is Result.Downloaded -> return result
                is Result.Failed -> return result
                Result.NotFound -> Log.i(TAG, "${candidate.fileName} is not published; trying next")
            }
        }
        return Result.NotFound
    }

    /**
     * Delete anything in the staging directory that is not one of [candidates].
     *
     * A full package is one to two gigabytes, so a superseded download or the `.part` of an
     * abandoned one is not clutter — it is a meaningful chunk of the user's storage, kept for a
     * build nothing will ever ask for again. Only whole packages are dropped; the `.part` of a
     * *current* candidate is what makes the download resumable and must survive.
     */
    private fun discardStalePackages(directory: File, candidates: List<OtaDownloadPlan.Candidate>) {
        val keep = candidates.flatMap { listOf(it.fileName, "${it.fileName}.part") }.toSet()
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in keep) {
                Log.i(TAG, "discarding stale package ${file.name}")
                file.delete()
            }
        }
    }

    private suspend fun fetch(
        directory: File,
        candidate: OtaDownloadPlan.Candidate,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val url = "${SystemBuild.otaServer()}/${candidate.fileName}"
        val target = File(directory, candidate.fileName)
        val part = File(directory, "${candidate.fileName}.part")
        try {
            directory.mkdirs()
            // A previous run may already have finished this one and been killed before it could
            // apply it. The file is still unverified either way, so this saves the transfer and
            // nothing else.
            if (target.exists() && target.length() > 0L) {
                return@withContext Result.Downloaded(target, candidate.incremental)
            }
            when (val status = stream(url, part, onProgress)) {
                Stream.Ok -> Unit
                Stream.NotFound -> return@withContext Result.NotFound
                is Stream.Error -> return@withContext Result.Failed(status.reason)
            }
            if (!promote(part, target)) {
                return@withContext Result.Failed("could not finalise the downloaded package")
            }
            Result.Downloaded(target, candidate.incremental)
        } catch (e: CancellationException) {
            // The `.part` stays on disk so the next run resumes rather than restarting.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download of ${candidate.fileName} failed", e)
            Result.Failed("could not download ${candidate.fileName}")
        }
    }

    private sealed interface Stream {
        data object Ok : Stream
        data object NotFound : Stream
        data class Error(val reason: String) : Stream
    }

    private suspend fun stream(
        url: String,
        part: File,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): Stream {
        part.parentFile?.mkdirs()
        var startOffset = if (part.exists()) part.length() else 0L
        // HTTPS only. The signature check is the real boundary, but there is no reason to let a
        // gigabyte of anything arrive over a connection nobody authenticated.
        val connection = URL(url).openConnection() as? HttpsURLConnection
            ?: return Stream.Error("refusing a non-HTTPS OTA URL")
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.useCaches = false
        if (startOffset > 0L) connection.setRequestProperty("Range", "bytes=$startOffset-")

        try {
            connection.connect()
            val append = when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> true
                // The server ignored the Range and is sending the whole file, so the partial we
                // already have is not a prefix of what is arriving. Start over.
                HttpURLConnection.HTTP_OK -> {
                    startOffset = 0L
                    false
                }

                HttpURLConnection.HTTP_NOT_FOUND -> return Stream.NotFound
                // A stale `.part` from a since-replaced artifact makes the server reject the
                // range as unsatisfiable. Drop it so the next attempt starts clean.
                HTTP_RANGE_NOT_SATISFIABLE -> {
                    part.delete()
                    return Stream.Error("the partial download no longer matches the server")
                }

                else -> return Stream.Error("the OTA server returned HTTP $code")
            }

            // contentLengthLong is the REMAINING bytes on a 206, not the file size.
            val remaining = connection.contentLengthLong
            val total = if (remaining >= 0) startOffset + remaining else -1L
            var soFar = startOffset
            var lastReport = 0L
            onProgress(soFar, total)

            FileOutputStream(part, append).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        soFar += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = now
                            onProgress(soFar, total)
                        }
                    }
                }
                // Force the bytes out before the file is renamed and handed to verifyPackage.
                output.fd.sync()
            }
            if (total > 0 && soFar != total) {
                return Stream.Error("the download ended early ($soFar of $total bytes)")
            }
            onProgress(soFar, total)
            return Stream.Ok
        } finally {
            connection.disconnect()
        }
    }

    /** Rename is atomic within a filesystem; the copy is the cross-filesystem fallback. */
    private fun promote(part: File, target: File): Boolean {
        if (target.exists()) target.delete()
        if (part.renameTo(target)) return true
        return try {
            part.copyTo(target, overwrite = true)
            part.delete()
            true
        } catch (e: IOException) {
            Log.w(TAG, "could not move ${part.name} into place", e)
            false
        }
    }
}
