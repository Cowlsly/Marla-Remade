package com.vayunmathur.library.downloadservice

import android.app.DownloadManager
import android.content.Context
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.downloadservice.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.round
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Internal unit of work: one on-disk [fileName] fetched from a single [url] with
 * an optional [sha256] verified after the download completes. Both the plain
 * `Triple(url, fileName, description)` API and the [ModelDownloadItem] API funnel
 * into this so the download loop only deals with one shape.
 *
 * [fileName] may contain a subdirectory (`small100/encoder.ncnn.bin`), which the
 * download loop creates on demand.
 */
internal data class DownloadSpec(
    val fileName: String,
    val description: String,
    val url: String,
    val sha256: String? = null,
)

private fun Triple<String, String, String>.toSpec() =
    DownloadSpec(fileName = second, description = third, url = first)

private fun ModelDownloadItem.toSpec() =
    DownloadSpec(fileName = fileName, description = description, url = url, sha256 = sha256)

@Composable
fun InitialDownloadChecker(
    ds: DataStoreUtils,
    filesToDownload: List<Triple<String, String, String>>,
    mainPage: @Composable () -> Unit
) = InitialDownloadCheckerSpecs(ds, filesToDownload.map { it.toSpec() }, mainPage)

/**
 * Model variant of [InitialDownloadChecker]: each [ModelDownloadItem] is fetched
 * from the self-hosted mirror only (no third-party fallback), with optional
 * SHA-256 integrity verification. This is the supply-chain mitigation #1 entry
 * point (see [ModelUrls]).
 */
@Composable
fun InitialModelDownloadChecker(
    ds: DataStoreUtils,
    models: List<ModelDownloadItem>,
    mainPage: @Composable () -> Unit
) = InitialDownloadCheckerSpecs(ds, models.map { it.toSpec() }, mainPage)

@Composable
private fun InitialDownloadCheckerSpecs(
    ds: DataStoreUtils,
    specs: List<DownloadSpec>,
    mainPage: @Composable () -> Unit
) {
    val context = LocalContext.current
    // Gate on the actual presence of the requested files on disk rather than a
    // DataStore flag: if they are all already downloaded, go straight to the app;
    // otherwise show the download screen. This self-heals if a file is deleted or
    // the flag drifts out of sync with disk.
    var filesPresent by remember {
        mutableStateOf(allFilesPresent(context, specs))
    }
    if (filesPresent) {
        mainPage()
    } else {
        InitialDownloadScreen(ds, specs, onAllDownloaded = { filesPresent = true })
    }
}

private fun allFilesPresent(
    context: Context,
    specs: List<DownloadSpec>,
): Boolean {
    val dir = context.getExternalFilesDir(null)
    return specs.all { File(dir, it.fileName).exists() }
}

@Composable
private fun InitialDownloadScreen(
    ds: DataStoreUtils,
    specs: List<DownloadSpec>,
    onAllDownloaded: () -> Unit = {},
) {
    val context = LocalContext.current

    // The download runs in a WorkManager job, not in this composition: it used to live in a
    // LaunchedEffect, so leaving the screen (or a configuration change) cancelled a multi-hundred-MB
    // transfer. The screen only enqueues and observes now; `.part` files let a stopped run resume.
    val workName = remember(specs) { ModelDownloadWorker.uniqueName(specs) }
    LaunchedEffect(specs) { ModelDownloadWorker.enqueue(context, specs) }

    val workInfo by remember(workName) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName)
    }.collectAsState(emptyList())
    val state = workInfo.firstOrNull()?.state

    LaunchedEffect(state) {
        if (state == WorkInfo.State.SUCCEEDED && allFilesPresent(context, specs)) {
            onAllDownloaded()
        }
    }

    Scaffold { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.initializing_system),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.downloading_required_components_for_this),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            // A silently-stuck screen was the old failure mode: every attempt threw, the loop gave
            // up, and nothing said so. Offer an explicit retry instead.
            if (state == WorkInfo.State.FAILED) {
                Text(
                    text = stringResource(R.string.download_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Button(onClick = { ModelDownloadWorker.retry(context, specs) }) {
                    Text(stringResource(R.string.retry))
                }
                Spacer(Modifier.height(24.dp))
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(specs, key = { it.fileName }) { spec ->
                    // Each item observes its own progress and speed from DataStore.
                    // "Done" is derived from progress (1.0) — no separate persisted
                    // flag that could disagree with disk state.
                    val progress by ds.doubleFlow("progress_${spec.fileName}").collectAsState(0.0)
                    val speedMbps by ds.doubleFlow("speed_${spec.fileName}").collectAsState(0.0)
                    val isDone = progress >= 0.999

                    FileProgressItem(
                        label = spec.description,
                        progress = progress,
                        speedMbps = speedMbps,
                        isDone = isDone
                    )
                }
            }
        }
    }
}

private const val SPEED_WINDOW_MS = 4000L

/** How often to publish progress/speed to DataStore while streaming. */
private const val PUBLISH_INTERVAL_MS = 500L

/** Bounded retry for transient network failures per file. */
private const val MAX_ATTEMPTS = 4
private const val RETRY_DELAY_MS = 2000L

private const val DOWNLOAD_BUFFER_SIZE = 1 shl 16
private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 30_000

private const val TAG = "ModelDownload"

/**
 * Mirror-only on-demand download: fetches each [ModelDownloadItem] from the
 * self-hosted mirror (no third-party fallback), with optional SHA-256
 * verification (supply-chain mitigation #1). Publishes the same `progress_*` /
 * `speed_*` DataStore keys as the initial screen and skips files already present
 * on disk. Returns whether every file ended up on disk and verified.
 *
 * This runs in the caller's scope, so it is for downloads the caller is already
 * waiting on; first-launch gating goes through [ModelDownloadWorker] instead.
 */
suspend fun downloadModels(
    context: Context,
    ds: DataStoreUtils,
    models: List<ModelDownloadItem>,
) = runDownloadsCore(context, ds, models.map { it.toSpec() })

/**
 * Self-managed streaming download loop. Each spec is streamed directly into the
 * app's [Context.getExternalFilesDir] with [java.net.HttpURLConnection], so the
 * files are ordinary app-owned files with no `DownloadManager` database row for
 * the system download provider to track or garbage-collect (the root cause of
 * the periodic model re-download).
 *
 * Files are processed sequentially; each publishes `progress_*` / `speed_*` for
 * the UI. On checksum mismatch the download is retried; on final failure any
 * `.part` file is left in place so the next run resumes it.
 *
 * Returns true only when every spec ended up on disk and verified, so the caller
 * ([ModelDownloadWorker]) can ask WorkManager to retry rather than reporting a
 * success that leaves the app gated forever.
 */
internal suspend fun runDownloadsCore(
    context: Context,
    ds: DataStoreUtils,
    specs: List<DownloadSpec>,
): Boolean = withContext(Dispatchers.IO) {
    val dir = context.getExternalFilesDir(null)
    var allDownloaded = true
    for (spec in specs) {
        // Sever any legacy DownloadManager claim on an already-present file before
        // we treat it as a plain app file (one-time, guarded — see fn docs).
        migrateLegacyDownload(context, ds, dir, spec)

        val file = File(dir, spec.fileName)
        // If the file already exists, verify SHA when pinned; a stale or tiny old
        // bundle (e.g. 593 KB dict vs 2.2 MB, or sherpa .onnx leftover) must be
        // re-downloaded.
        if (file.exists()) {
            if (checksumOk(file, spec.sha256)) {
                ds.setDouble("progress_${spec.fileName}", 1.0)
                continue
            } else {
                file.delete()
                ds.setDouble("progress_${spec.fileName}", 0.0)
            }
        }
        if (!downloadSpec(ds, dir, spec)) allDownloaded = false
    }
    allDownloaded
}

/**
 * Downloads a single [spec] into `<fileName>.part`, verifies its SHA-256, then
 * atomically renames it to the final name. Retries transient failures up to
 * [MAX_ATTEMPTS]; on a checksum mismatch the partial is discarded and the file
 * is re-fetched from scratch. On final failure any `.part` is left for the next
 * run to resume, and false is returned so the caller can surface it.
 */
private suspend fun downloadSpec(
    ds: DataStoreUtils,
    dir: File?,
    spec: DownloadSpec,
): Boolean {
    val finalFile = File(dir, spec.fileName)
    val partFile = File(dir, "${spec.fileName}.part")

    repeat(MAX_ATTEMPTS) { attempt ->
        try {
            streamToPart(ds, spec, partFile)
            if (checksumOk(partFile, spec.sha256)) {
                finalizeDownload(ds, spec, partFile, finalFile)
                return true
            }
            // Corrupt/tampered copy — drop the partial and re-fetch from scratch.
            Log.w(TAG, "checksum mismatch for ${spec.fileName}; refetching")
            partFile.delete()
        } catch (e: CancellationException) {
            // Cooperative cancellation (worker stopped): keep the `.part` so the
            // next run resumes via the Range header.
            throw e
        } catch (e: Exception) {
            // Transient network error — keep the `.part` for resume and back off.
            Log.w(TAG, "attempt ${attempt + 1}/$MAX_ATTEMPTS failed for ${spec.fileName}", e)
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
    }
    Log.e(TAG, "giving up on ${spec.fileName} after $MAX_ATTEMPTS attempts")
    return false
}

/**
 * Streams [spec] from the network into [partFile]. Resumes an existing partial
 * with a `Range` request, appending on HTTP 206 and restarting (truncating) on
 * 200. Publishes throttled `progress_*` / `speed_*` while streaming.
 */
private suspend fun streamToPart(
    ds: DataStoreUtils,
    spec: DownloadSpec,
    partFile: File,
) {
    // fileName can name a subdirectory ("small100/encoder.ncnn.bin"). FileOutputStream does not
    // create missing parents, so without this every attempt died with ENOENT before a single byte
    // was written — which is exactly how the SMaLL-100 download hung at 0% with no error.
    partFile.parentFile?.mkdirs()
    var startOffset = if (partFile.exists()) partFile.length() else 0L
    val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        requestMethod = "GET"
        if (startOffset > 0) setRequestProperty("Range", "bytes=$startOffset-")
    }
    try {
        conn.connect()
        // Mirror MapTileCache.kt: treat 206 as a resume, 200 as a full restart
        // (the server ignored our Range).
        val append = when (val code = conn.responseCode) {
            HttpURLConnection.HTTP_PARTIAL -> true
            HttpURLConnection.HTTP_OK -> {
                startOffset = 0L
                false
            }
            else -> throw IOException("Unexpected HTTP $code for ${spec.url}")
        }
        // contentLengthLong is the *remaining* bytes; add the resume offset for the total.
        val remaining = conn.contentLengthLong
        val total = if (remaining >= 0) startOffset + remaining else -1L

        val samples = ArrayDeque<Pair<Long, Long>>()
        var soFar = startOffset
        var lastPublish = 0L

        if (total > 0) {
            ds.setDouble(
                "progress_${spec.fileName}",
                (soFar.toDouble() / total).coerceIn(0.0, 1.0)
            )
        }

        FileOutputStream(partFile, append).use { output ->
            conn.inputStream.use { input ->
                val buf = ByteArray(DOWNLOAD_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    soFar += n

                    val now = System.currentTimeMillis()
                    if (now - lastPublish >= PUBLISH_INTERVAL_MS) {
                        lastPublish = now
                        if (total > 0) {
                            ds.setDouble(
                                "progress_${spec.fileName}",
                                (soFar.toDouble() / total).coerceIn(0.0, 1.0)
                            )
                        }
                        // Moving-average speed over the last SPEED_WINDOW_MS so the
                        // reading stays stable across bursty reads.
                        samples.addLast(now to soFar)
                        while (samples.size > 1 && now - samples.first().first > SPEED_WINDOW_MS) {
                            samples.removeFirst()
                        }
                        val (oldestTime, oldestBytes) = samples.first()
                        val spanSec = (now - oldestTime) / 1000.0
                        if (spanSec >= 0.5) {
                            val speedMbps = ((soFar - oldestBytes) * 8.0) / 1_000_000.0 / spanSec
                            ds.setDouble("speed_${spec.fileName}", speedMbps.coerceAtLeast(0.0))
                        }
                    }
                }
            }
            output.fd.sync()
        }
    } finally {
        conn.disconnect()
    }
}

/** Atomically promotes a verified [partFile] to [finalFile] and marks it done. */
private suspend fun finalizeDownload(
    ds: DataStoreUtils,
    spec: DownloadSpec,
    partFile: File,
    finalFile: File,
) {
    if (finalFile.exists()) finalFile.delete()
    if (!partFile.renameTo(finalFile)) {
        // Rename can fail across some filesystems; fall back to a copy.
        partFile.copyTo(finalFile, overwrite = true)
        partFile.delete()
    }
    ds.setDouble("progress_${spec.fileName}", 1.0)
    ds.setDouble("speed_${spec.fileName}", 0.0)
}

/**
 * One-time, crash-guarded severing of a legacy `DownloadManager` claim on an
 * already-present file. The file currently on disk was created by
 * `DownloadManager` and still has a tracking row (`dlid_<fileName>`), so the
 * download provider could sweep it one last time. To drop that row without
 * re-downloading the (multi-GB) file, we move the real file aside first so
 * `DownloadManager.remove()` finds nothing to delete, then move it back.
 *
 * This is the only remaining reference to `DownloadManager`; once the id is
 * cleared it never runs again for that file.
 */
private suspend fun migrateLegacyDownload(
    context: Context,
    ds: DataStoreUtils,
    dir: File?,
    spec: DownloadSpec,
) {
    val finalFile = File(dir, spec.fileName)
    val migrating = File(dir, "${spec.fileName}.migrating")

    // Crash recovery: a leftover `.migrating` (with no final file) means a prior
    // run renamed away but didn't finish — restore it before doing anything else.
    if (migrating.exists() && !finalFile.exists()) {
        migrating.renameTo(finalFile)
    }

    val dlid = ds.getLong("dlid_${spec.fileName}") ?: 0L
    if (dlid <= 0L) return
    if (!finalFile.exists()) {
        // Nothing to protect; just drop the stale id.
        ds.setLong("dlid_${spec.fileName}", 0L)
        return
    }

    try {
        if (finalFile.renameTo(migrating)) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            // The tracked path is now empty, so this drops only the DB row.
            dm.remove(dlid)
            migrating.renameTo(finalFile)
        }
    } catch (_: Exception) {
        // Best effort: ensure the file is restored if anything went wrong.
        if (!finalFile.exists() && migrating.exists()) migrating.renameTo(finalFile)
    } finally {
        ds.setLong("dlid_${spec.fileName}", 0L)
    }
}

/**
 * True when [file] matches [expected] SHA-256. A null [expected] passes
 * unconditionally (integrity is opt-in until the mirror publishes checksums).
 */
private fun checksumOk(file: File, expected: String?): Boolean {
    val exp = expected ?: return true
    if (!file.exists()) return false
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    val actual = md.digest().joinToString("") { "%02x".format(it) }
    return actual.equals(exp, ignoreCase = true)
}

@Composable
fun FileProgressItem(
    label: String,
    progress: Double,
    speedMbps: Double,
    isDone: Boolean
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.toFloat(),
        label = "smooth_progress"
    )

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1.0f),
                maxLines = 1
            )

            if (!isDone && progress > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = stringResource(R.string.mbps, speedMbps.round(1).toString()),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = if (isDone) stringResource(R.string.completed) else stringResource(R.string.downloading),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "${(progress * 100).round(1)}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            strokeCap = StrokeCap.Round,
            color = if (isDone) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        )
    }
}
