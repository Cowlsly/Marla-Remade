package com.vayunmathur.library.downloadservice

/**
 * Centralized model URL config for supply-chain mitigation #1.
 * Models are served ONLY from the self-hosted mirror (no HuggingFace fallback).
 * The server must host each file under https://data.vayunmathur.com/models/
 * and set Content-Length + ETag.
 *
 * To populate the mirror, upload these HuggingFace source files under the given
 * mirror paths (left = mirror path, right = HuggingFace source):
 *
 *   models/gemma-4-E2B-it.litertlm
 *     <- https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm
 */
object ModelUrls {
    const val MIRROR_BASE = "https://data.vayunmathur.com/models/"

    // On-disk file name.
    const val GEMMA_FILE = "gemma4-2b.litertlm"

    // Mirror download URL.
    const val GEMMA_URL = "${MIRROR_BASE}gemma-4-E2B-it.litertlm"

    // SHA-256 integrity check (verified against the file uploaded to the mirror).
    const val GEMMA_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    val GEMMA = ModelDownloadItem(GEMMA_URL, GEMMA_FILE, "Model", GEMMA_SHA256)

    /** Everything downloaded on OpenAssistant first launch. */
    val INITIAL = listOf(GEMMA)
}

/** A single model file fetched from the self-hosted mirror, with optional SHA-256. */
data class ModelDownloadItem(
    val url: String,
    val fileName: String,
    val description: String,
    val sha256: String? = null,
)
