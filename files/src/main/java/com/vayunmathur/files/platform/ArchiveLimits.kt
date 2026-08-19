package com.vayunmathur.files.platform

import java.io.File

/**
 * The limits [UnzipWorker] extracts within, kept separate from the worker so they can be tested
 * without WorkManager or a notification channel.
 *
 * Extraction is bounded by how much room the device actually has rather than by a fixed number of
 * megabytes: an archive that fits is always allowed, and one that would fill the disk never is. That
 * matters because the app holds MANAGE_EXTERNAL_STORAGE, so running out of space hurts the whole
 * device rather than just this app.
 */
object ArchiveLimits {

    /**
     * Space deliberately left unused. Android misbehaves badly on a full volume, so extraction stops
     * well before the last byte.
     */
    private const val FREE_SPACE_HEADROOM_BYTES = 512L * 1024 * 1024

    /** Enough for any real archive, low enough that a million-entry archive cannot stall the app. */
    const val MAX_ENTRIES = 100_000

    /** How many bytes an extraction into a volume with [freeBytes] available may write. */
    fun extractionBudget(freeBytes: Long): Long =
        (freeBytes - FREE_SPACE_HEADROOM_BYTES).coerceAtLeast(0)

    /**
     * Total size the archive says it will expand to, or null if any entry declines to say.
     *
     * Only good enough to turn away honestly oversized archives: the value comes from the archive
     * itself, so a crafted one can under-report and has to be caught while writing instead.
     */
    fun declaredUncompressedSize(sizes: Sequence<Long>): Long? {
        var total = 0L
        for (size in sizes) {
            if (size < 0) return null
            total += size
        }
        return total
    }

    /**
     * Where an entry named [entryName] belongs under [destDirCanonical], or null if it would land
     * outside it — a Zip Slip attempt.
     *
     * The separator matters: comparing paths by raw prefix lets an entry escape into a sibling whose
     * name merely starts the same way, so `../out-evil/x` would pass for a destination of `out`.
     */
    fun resolveEntry(destDirCanonical: File, entryName: String): File? {
        val resolved = File(destDirCanonical, entryName).canonicalFile
        val root = destDirCanonical.path
        val inside = resolved.path == root || resolved.path.startsWith(root + File.separator)
        return resolved.takeIf { inside }
    }
}

/** Thrown when an archive tries to write past its budget or entry ceiling. */
class ArchiveTooLargeException : Exception("Archive exceeds its extraction budget")
