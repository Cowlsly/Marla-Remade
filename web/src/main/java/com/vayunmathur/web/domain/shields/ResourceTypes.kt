package com.vayunmathur.web.domain.shields

/**
 * Maps a WebView subresource request onto one of adblock-rust's request types.
 *
 * `WebResourceRequest` carries no resource type, so this reconstructs it the way
 * Chromium would have labelled the request: `Sec-Fetch-Dest` when the WebView sends it,
 * then the `Accept` header, then the URL's file extension. Getting this right matters
 * because a large share of filters are qualified with `$script`, `$image`, `$xhr` etc.
 *
 * Kept free of `android.*` so it can be unit tested on the JVM.
 */
object ResourceTypes {

    /** adblock-rust's fallback bucket, used when nothing more specific is known. */
    const val OTHER = "other"

    private val BY_FETCH_DEST = mapOf(
        "audio" to "media",
        "audioworklet" to "script",
        "document" to "document",
        "embed" to "object",
        "empty" to "xmlhttprequest",
        "font" to "font",
        "frame" to "subdocument",
        "iframe" to "subdocument",
        "image" to "image",
        "manifest" to OTHER,
        "object" to "object",
        "paintworklet" to "script",
        "report" to "other",
        "script" to "script",
        "serviceworker" to "script",
        "sharedworker" to "script",
        "style" to "stylesheet",
        "track" to OTHER,
        "video" to "media",
        "worker" to "script",
        "xslt" to "xlst",
    )

    private val BY_EXTENSION = mapOf(
        "js" to "script",
        "mjs" to "script",
        "css" to "stylesheet",
        "gif" to "image",
        "png" to "image",
        "jpg" to "image",
        "jpeg" to "image",
        "webp" to "image",
        "svg" to "image",
        "ico" to "image",
        "bmp" to "image",
        "avif" to "image",
        "woff" to "font",
        "woff2" to "font",
        "ttf" to "font",
        "otf" to "font",
        "eot" to "font",
        "mp3" to "media",
        "mp4" to "media",
        "webm" to "media",
        "ogg" to "media",
        "m4a" to "media",
        "wav" to "media",
        "m3u8" to "media",
        "json" to "xmlhttprequest",
        "html" to "subdocument",
        "htm" to "subdocument",
    )

    /**
     * @param isMainFrame from `WebResourceRequest.isForMainFrame`
     * @param headers from `WebResourceRequest.requestHeaders`, looked up case-insensitively
     */
    fun of(url: String, isMainFrame: Boolean, headers: Map<String, String>): String {
        if (isMainFrame) return "document"

        header(headers, "Sec-Fetch-Dest")?.lowercase()?.let { dest ->
            BY_FETCH_DEST[dest]?.let { return it }
        }
        fromAccept(header(headers, "Accept"))?.let { return it }
        return BY_EXTENSION[extensionOf(url)] ?: OTHER
    }

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /**
     * Chromium sends a distinctive `Accept` per destination. `text/html` is deliberately
     * not mapped: navigations already returned above, and an `Accept` of `text/html` on a
     * subresource is the generic default that XHR also sends.
     */
    private fun fromAccept(accept: String?): String? {
        val value = accept?.lowercase() ?: return null
        return when {
            value.startsWith("text/css") -> "stylesheet"
            value.startsWith("image/") -> "image"
            value.startsWith("audio/") || value.startsWith("video/") -> "media"
            value.contains("application/font") || value.contains("font/") -> "font"
            else -> null
        }
    }

    private fun extensionOf(url: String): String {
        val path = url.substringBefore('#').substringBefore('?')
        val lastSegment = path.substringAfterLast('/', "")
        if (!lastSegment.contains('.')) return ""
        return lastSegment.substringAfterLast('.').lowercase()
    }
}
