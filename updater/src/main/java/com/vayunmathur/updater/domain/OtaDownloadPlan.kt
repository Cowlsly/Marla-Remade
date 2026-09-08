package com.vayunmathur.updater.domain

/**
 * Which artifacts to try, in order, for a given target build.
 *
 * The incremental comes first because it is tens of megabytes against the full package's one to
 * two gigabytes. It is also frequently absent: an incremental is published against one exact
 * source build, so a device that skipped a release has no incremental to fetch. That is the
 * normal case, not an error — a 404 on the first candidate means move to the next one, and only
 * exhausting the list is a failure.
 */
object OtaDownloadPlan {

    data class Candidate(
        val fileName: String,
        /** Carried through so the caller can say which kind it ended up applying. */
        val incremental: Boolean,
    )

    /**
     * Ordered candidates for [targetBuild] on [device].
     *
     * [currentBuild] is what is running. When it is blank we cannot name an incremental — its
     * filename embeds the source build — so the full package is the only option.
     */
    fun candidates(device: String, currentBuild: String, targetBuild: String): List<Candidate> {
        val full = Candidate(
            fileName = OtaArtifacts.full(device, targetBuild, streaming = false),
            incremental = false,
        )
        if (currentBuild.isBlank() || currentBuild == targetBuild) return listOf(full)
        return listOf(
            Candidate(
                fileName = OtaArtifacts.incremental(
                    device,
                    currentBuild,
                    targetBuild,
                    streaming = false,
                ),
                incremental = true,
            ),
            full,
        )
    }
}
