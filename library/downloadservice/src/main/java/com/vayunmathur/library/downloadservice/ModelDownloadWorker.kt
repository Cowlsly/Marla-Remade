package com.vayunmathur.library.downloadservice

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.library.util.DataStoreUtils
import java.util.concurrent.TimeUnit

/**
 * Runs the model download outside any composition.
 *
 * The download used to run in a `LaunchedEffect` on the gating screen, so it was cancelled by a
 * rotation, a back press or the app being backgrounded, and a partial transfer of a multi-hundred-MB
 * model only resumed if the user happened to reopen that exact screen. Here it survives all of
 * those, and a failed run is retried by WorkManager with exponential backoff instead of being
 * swallowed.
 *
 * This is a plain background worker, so the platform stops it after roughly ten minutes of
 * execution. That is fine because the work is resumable: partial bytes live in `<fileName>.part`
 * and the next run continues with a `Range` request, so a large bundle completes across several
 * runs. Promote it to a foreground worker (`setForeground`) if it needs to finish in one pass.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val specs = readSpecs(inputData)
        if (specs.isEmpty()) return Result.failure()

        val ds = DataStoreUtils.getInstance(applicationContext)
        return when {
            runDownloadsCore(applicationContext, ds, specs) -> Result.success()
            // Keep retrying transient problems (flaky network, mirror hiccup) but eventually stop so
            // the screen can offer a retry rather than backing off forever.
            runAttemptCount + 1 >= MAX_RUN_ATTEMPTS -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        private const val KEY_URLS = "urls"
        private const val KEY_FILE_NAMES = "fileNames"
        private const val KEY_DESCRIPTIONS = "descriptions"
        private const val KEY_HASHES = "hashes"

        /** Absent SHA-256, encoded as an empty string because [Data] holds no nulls. */
        private const val NO_HASH = ""

        private const val MAX_RUN_ATTEMPTS = 6
        private const val BACKOFF_SECONDS = 30L

        /**
         * Stable unique-work name for [specs], so re-entering the screen observes the run already in
         * flight instead of starting a second one.
         */
        internal fun uniqueName(specs: List<DownloadSpec>): String {
            val key = specs.joinToString("\u0000") { it.fileName }.hashCode()
            return "model_download_${key.toUInt().toString(16)}"
        }

        /** Enqueues the download, keeping any run already in progress for the same [specs]. */
        internal fun enqueue(context: Context, specs: List<DownloadSpec>) =
            enqueue(context, specs, ExistingWorkPolicy.KEEP)

        /** Restarts the download after a failure, replacing the failed run. */
        internal fun retry(context: Context, specs: List<DownloadSpec>) =
            enqueue(context, specs, ExistingWorkPolicy.REPLACE)

        private fun enqueue(
            context: Context,
            specs: List<DownloadSpec>,
            policy: ExistingWorkPolicy,
        ) {
            val data = Data.Builder()
                .putStringArray(KEY_URLS, specs.map { it.url }.toTypedArray())
                .putStringArray(KEY_FILE_NAMES, specs.map { it.fileName }.toTypedArray())
                .putStringArray(KEY_DESCRIPTIONS, specs.map { it.description }.toTypedArray())
                .putStringArray(KEY_HASHES, specs.map { it.sha256 ?: NO_HASH }.toTypedArray())
                .build()

            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName(specs), policy, request)
        }

        private fun readSpecs(data: Data): List<DownloadSpec> {
            val urls = data.getStringArray(KEY_URLS) ?: return emptyList()
            val fileNames = data.getStringArray(KEY_FILE_NAMES) ?: return emptyList()
            val descriptions = data.getStringArray(KEY_DESCRIPTIONS) ?: return emptyList()
            val hashes = data.getStringArray(KEY_HASHES) ?: return emptyList()
            if (fileNames.size != urls.size ||
                descriptions.size != urls.size ||
                hashes.size != urls.size
            ) {
                return emptyList()
            }
            return urls.indices.map { i ->
                DownloadSpec(
                    fileName = fileNames[i],
                    description = descriptions[i],
                    url = urls[i],
                    sha256 = hashes[i].takeIf { it != NO_HASH },
                )
            }
        }
    }
}
