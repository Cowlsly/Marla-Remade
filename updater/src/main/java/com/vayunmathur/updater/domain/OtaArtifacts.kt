package com.vayunmathur.updater.domain

/**
 * The names of the two OTA artifacts for a given target build.
 *
 * Both live beside the metadata on the OTA server. The incremental is tried first and is
 * usually tens of megabytes; the full package is 1-2 GB and is the fallback when no incremental
 * was published for this exact source build, which is the normal case after skipping a release.
 *
 * `-streaming` variants exist because update_engine can read the payload straight out of an
 * HTTPS range request instead of a downloaded file. The streaming zip has the same contents but
 * is laid out so `payload.bin` is reachable without the whole archive.
 */
object OtaArtifacts {

    fun incremental(device: String, from: String, to: String, streaming: Boolean): String =
        "$device${suffix(streaming)}-incremental-$from-$to.zip"

    fun full(device: String, to: String, streaming: Boolean): String =
        "$device${suffix(streaming)}-ota_update-$to.zip"

    private fun suffix(streaming: Boolean) = if (streaming) "-streaming" else ""
}
