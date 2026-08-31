package com.vayunmathur.pdf.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A parsed PDF for the "safe" viewer, backed by the native Rust renderer.
 *
 * Reads the [Uri] bytes through the [Context]'s content resolver, hands them to
 * [PdfNative.openDocument], and exposes the [pageCount] plus a per-page
 * [renderPage]. Rendered pages are cached so scrolling back does not re-decode.
 * All native work happens on [Dispatchers.IO]; callers must [close] when done.
 *
 * v2: adds saveCompressed, flattenDocument, extractPage, extractText wrappers and cache invalidation for wire version upgrades.
 */
class SafePdfDocument private constructor(
    private val handle: Long,
    val pageCount: Int,
) {
    // Bounded LRU to avoid OOM. Accounted in BYTES via [pageWeightBytes] — see the note on
    // [MAX_CACHED_BYTES] for why counting only bitmap pixels was blind to the pages that
    // actually cause the OOM. Every add/subtract goes through the one helper, because the
    // original budget-drift bug came from four hand-duplicated copies of the arithmetic.
    private var cachedBytes: Long = 0L
    /**
     * Every page dimension ever decoded, keyed by page index. Deliberately NOT evicted with
     * [cache]: it is two floats per page, and the placeholder shown before a page is decoded
     * needs its true aspect ratio. Sizing an undecoded page as letter-portrait and then
     * resizing it once the real dimensions arrive changes the item's height mid-scroll, which
     * moves everything below it — very visible in a document with a landscape insert, or when
     * scrolling back to a page the LRU has dropped.
     */
    private val pageSizes = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()

    // Evicting a page must NOT recycle its bitmaps: the same SafePdfPage instance is
    // still referenced by whatever composable is drawing it, and Canvas.drawBitmap
    // throws "Cannot draw recycled bitmaps" from the draw phase. Dropping the last
    // reference is enough — the GC reclaims the pixels.
    private val cache: MutableMap<Int, SafePdfPage> = object : LinkedHashMap<Int, SafePdfPage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SafePdfPage>?): Boolean {
            if (size <= MAX_CACHED_PAGES) return false
            eldest?.value?.let { cachedBytes = (cachedBytes - pageWeightBytes(it)).coerceAtLeast(0L) }
            return true
        }
    }

    /**
     * Rough retained size of [page] in bytes. Decoded bitmaps are exact (ARGB_8888, four bytes
     * per pixel); everything else is charged a flat [PRIMITIVE_WEIGHT_BYTES] estimate, which is
     * deliberately an order-of-magnitude figure rather than a measurement — the point is to
     * bound the primitive COUNT, not to predict the heap precisely.
     */
    private fun pageWeightBytes(page: SafePdfPage): Long {
        var bytes = 0L
        for (prim in page.primitives) {
            bytes += PRIMITIVE_WEIGHT_BYTES
            if (prim is PdfPrimitive.Image) {
                val b = prim.bitmap
                if (b != null) bytes += b.width.toLong() * b.height.toLong() * 4L
            }
        }
        return bytes
    }

    /** Decode page [index] (0-based), or `null` if the native render fails. */
    suspend fun renderPage(index: Int): SafePdfPage? = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[index] }?.let { return@withContext it }
        // A single unrenderable page must not take down the viewer: the native
        // side throws a RuntimeException on an uncaught panic (see the
        // catch_unwind boundary in jni_bindings.rs), and a corrupt wire buffer
        // could throw during parse. Degrade either to a skipped (null) page.
        val bytes = runCatching { PdfNative.renderPage(handle, index) }
            .onFailure { android.util.Log.w(TAG, "native renderPage threw for page $index", it) }
            .getOrNull()
        if (bytes == null) {
            android.util.Log.w(TAG, "native renderPage returned no data for page $index")
            return@withContext null
        }
        val page = runCatching { SafePdfParser.parse(bytes) }
            .onFailure {
                android.util.Log.w(TAG, "wire parse failed for page $index (${bytes.size} bytes)", it)
            }
            .getOrNull()
            ?: return@withContext null
        pageSizes[index] = floatArrayOf(page.width, page.height)
        synchronized(cache) {
            cachedBytes += pageWeightBytes(page)
            while (cachedBytes > MAX_CACHED_BYTES && cache.isNotEmpty()) {
                val eldest = cache.entries.iterator().next()
                cache.remove(eldest.key)?.let { evicted ->
                    cachedBytes = (cachedBytes - pageWeightBytes(evicted)).coerceAtLeast(0L)
                }
            }
            // The cache lookup at the top of renderPage is outside this lock, so two
            // coroutines can both miss for the same index and both insert. A replacing put
            // does not call removeEldestEntry, so the displaced page's weight was never
            // subtracted and cachedBytes drifted upward for the rest of the session —
            // permanently over-budget, evicting pages that fit and re-decoding them.
            cache.put(index, page)?.let { displaced ->
                cachedBytes = (cachedBytes - pageWeightBytes(displaced)).coerceAtLeast(0L)
            }
        }
        page
    }

    /**
     * Width/height ratio of page [index] if it has been decoded at least once, else `null`.
     * Lets the viewer lay out a not-yet-decoded page at its real height instead of guessing.
     */
    fun knownAspectRatio(index: Int): Float? =
        pageSizes[index]?.takeIf { it[0] > 0f && it[1] > 0f }?.let { it[0] / it[1] }

    /** Release native resources. Idempotent-safe to call once. */
    fun close() {
        synchronized(cache) {
            cache.clear()
            cachedBytes = 0L
        }
        PdfNative.closeDocument(handle)
    }

    private fun invalidate(index: Int) {
        synchronized(cache) {
            cache.remove(index)?.let {
                cachedBytes = (cachedBytes - pageWeightBytes(it)).coerceAtLeast(0L)
            }
        }
    }

    /**
     * Decode a listing buffer, degrading a malformed one to an empty list.
     *
     * [renderPage] already catches a wire-parse failure because a throw there becomes a null
     * page. These listings had no equivalent: the decoders throw on a truncated or desynced
     * buffer, none of the callers below caught, and every one of them is consumed from a
     * `produceState` in [com.vayunmathur.pdf.ui.SafePdfViewerScreen] — so the exception left
     * the coroutine and took the composition down. An empty overlay is a far better outcome
     * than losing the viewer, and it matches what a null buffer from the native side already
     * produces.
     */
    private fun <T> decodeListing(what: String, index: Int, bytes: ByteArray?, decode: (ByteArray) -> List<T>): List<T> {
        if (bytes == null) return emptyList()
        return runCatching { decode(bytes) }
            .onFailure { android.util.Log.w(TAG, "$what listing failed to decode for page $index", it) }
            .getOrDefault(emptyList())
    }

    /** Annotations on [index] for the editing overlay. */
    suspend fun annotations(index: Int): List<SafeAnnotation> = withContext(Dispatchers.IO) {
        decodeListing("annotation", index, PdfNative.listAnnotations(handle, index), SafePdfParser::parseAnnotations)
    }

    /** AcroForm widget fields on [index]. */
    suspend fun formFields(index: Int): List<SafeFormField> = withContext(Dispatchers.IO) {
        decodeListing("form field", index, PdfNative.listFormFields(handle, index), SafePdfParser::parseFormFields)
    }

    /** Link annotations on [index]. */
    suspend fun links(index: Int): List<SafeLink> = withContext(Dispatchers.IO) {
        decodeListing("link", index, PdfNative.listLinks(handle, index), SafePdfParser::parseLinks)
    }

    suspend fun addText(
        index: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, size: Float, text: String,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addTextAnnotation(handle, index, x0, y0, x1, y1, argb, size, text)
            .also { invalidate(index) }
    }

    suspend fun addHighlight(
        index: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addHighlight(handle, index, x0, y0, x1, y1, argb).also { invalidate(index) }
    }

    /** [kind]: 0 underline, 1 strikeout, 2 squiggly. */
    suspend fun addTextMarkup(
        index: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, kind: Int,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addTextMarkup(handle, index, x0, y0, x1, y1, argb, kind).also { invalidate(index) }
    }

    suspend fun addNote(
        index: Int, x: Float, y: Float, argb: Int, text: String,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addNote(handle, index, x, y, argb, text).also { invalidate(index) }
    }

    suspend fun addCallout(
        index: Int, ax: Float, ay: Float, bx: Float, by: Float, argb: Int, size: Float, text: String,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addCallout(handle, index, ax, ay, bx, by, argb, size, text).also { invalidate(index) }
    }

    suspend fun addRect(
        index: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, lineWidth: Float,
        fill: Boolean,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addRectAnnotation(handle, index, x0, y0, x1, y1, argb, lineWidth, fill)
            .also { invalidate(index) }
    }

    suspend fun addOval(
        index: Int, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, lineWidth: Float,
        fill: Boolean,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addCircleAnnotation(handle, index, x0, y0, x1, y1, argb, lineWidth, fill)
            .also { invalidate(index) }
    }

    /** [pts] are flat page-space x,y pairs. [closed] fills/closes the path. */
    suspend fun addPoly(
        index: Int, pts: FloatArray, argb: Int, lineWidth: Float, fill: Boolean, closed: Boolean,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addPolyAnnotation(handle, index, argb, lineWidth, fill, closed, pts)
            .also { invalidate(index) }
    }

    suspend fun addInk(
        index: Int, argb: Int, lineWidth: Float, pts: FloatArray,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addInkAnnotation(handle, index, argb, lineWidth, pts).also { invalidate(index) }
    }

    suspend fun addImageStamp(
        index: Int, x0: Float, y0: Float, x1: Float, y1: Float, imgW: Int, imgH: Int, jpeg: ByteArray,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addImageStamp(handle, index, x0, y0, x1, y1, imgW, imgH, jpeg)
            .also { invalidate(index) }
    }

    suspend fun moveAnnotation(
        index: Int, annotId: Long, x0: Float, y0: Float, x1: Float, y1: Float,
    ): Boolean = withContext(Dispatchers.IO) {
        PdfNative.updateAnnotationRect(handle, index, annotId, x0, y0, x1, y1).also { invalidate(index) }
    }

    suspend fun editText(index: Int, annotId: Long, text: String): Boolean =
        withContext(Dispatchers.IO) {
            PdfNative.updateTextAnnotation(handle, annotId, text).also { invalidate(index) }
        }

    suspend fun deleteAnnotation(index: Int, annotId: Long): Boolean = withContext(Dispatchers.IO) {
        PdfNative.deleteAnnotation(handle, index, annotId).also { invalidate(index) }
    }

    /** Detach (hide) an annotation, keeping it for undo. */
    suspend fun detachAnnotation(index: Int, annotId: Long): Boolean = withContext(Dispatchers.IO) {
        PdfNative.detachAnnotation(handle, index, annotId).also { invalidate(index) }
    }

    /** Re-attach a previously detached annotation. */
    suspend fun reattachAnnotation(index: Int, annotId: Long): Boolean = withContext(Dispatchers.IO) {
        PdfNative.reattachAnnotation(handle, index, annotId).also { invalidate(index) }
    }

    /** Duplicate an annotation shifted by (dx,dy); returns the new id (0 on failure). */
    suspend fun duplicateAnnotation(
        index: Int, annotId: Long, dx: Float, dy: Float,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.duplicateAnnotation(handle, index, annotId, dx, dy).also { invalidate(index) }
    }

    suspend fun setTextField(index: Int, widgetId: Long, value: String): Boolean =
        withContext(Dispatchers.IO) {
            PdfNative.setTextField(handle, widgetId, value).also { invalidate(index) }
        }

    suspend fun setCheckbox(index: Int, widgetId: Long, on: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            PdfNative.setCheckbox(handle, widgetId, on).also { invalidate(index) }
        }

    suspend fun setChoiceField(index: Int, widgetId: Long, value: String): Boolean =
        withContext(Dispatchers.IO) {
            PdfNative.setChoiceField(handle, widgetId, value).also { invalidate(index) }
        }

    /** Serialize the (possibly edited) document to PDF bytes. */
    suspend fun save(): ByteArray? = withContext(Dispatchers.IO) { PdfNative.saveDocument(handle) }

    /** Serialize with streams compressed + unused objects pruned - wrapper for saveCompressed native */
    suspend fun saveCompressed(): ByteArray? = withContext(Dispatchers.IO) {
        PdfNative.saveCompressed(handle).also { synchronized(cache) { cache.clear(); cachedBytes = 0L } }
    }

    /** Flatten annotations into page content - wrapper for flattenDocument native */
    suspend fun flattenDocument(): Boolean = withContext(Dispatchers.IO) {
        PdfNative.flattenDocument(handle).also { synchronized(cache) { cache.clear(); cachedBytes = 0L } }
    }

    /** Extract page [index] into standalone one-page PDF bytes */
    suspend fun extractPage(index: Int): ByteArray? = withContext(Dispatchers.IO) {
        PdfNative.extractPage(handle, index)
    }

    /** Extract document's visible text */
    suspend fun extractText(): String? = withContext(Dispatchers.IO) {
        PdfNative.extractText(handle)
    }

    /** Add a redaction annotation over the rect; returns id (0 on failure). */
    suspend fun addRedaction(
        index: Int, x0: Float, y0: Float, x1: Float, y1: Float,
    ): Long = withContext(Dispatchers.IO) {
        PdfNative.addRedaction(handle, index, x0, y0, x1, y1).also { invalidate(index) }
    }

    /** Permanently remove content under redaction annotations. */
    suspend fun applyRedactions(): Boolean = withContext(Dispatchers.IO) {
        PdfNative.applyRedactions(handle).also { synchronized(cache) { cache.clear(); cachedBytes = 0L } }
    }

    /** Whether any redaction annotations exist (to show the Apply-redactions action). */
    suspend fun hasRedactions(): Boolean = withContext(Dispatchers.IO) { PdfNative.hasRedactions(handle) }

    /** The document outline (bookmarks), empty if none. */
    suspend fun outline(): List<SafeOutlineItem> = withContext(Dispatchers.IO) {
        decodeListing("outline", -1, PdfNative.listOutline(handle), SafePdfParser::parseOutline)
    }

    /** Full-text search across all pages with case-sensitive toggle (Phase 7). Default case-insensitive for backward compat. */
    suspend fun search(query: String, caseSensitive: Boolean = false): List<SafeSearchMatch> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyList()
        else {
            val bytes = if (caseSensitive) {
                PdfNative.searchDocumentCaseSensitive(handle, query)
            } else {
                PdfNative.searchDocument(handle, query)
            }
            decodeListing("search match", -1, bytes, SafePdfParser::parseSearchMatches)
        }
    }

    /** Case-insensitive full-text search across all pages. */
    suspend fun searchLegacy(query: String): List<SafeSearchMatch> = search(query, false)

    /** Prebuild the search text index so the first query is instant. */
    suspend fun prewarmSearch() = withContext(Dispatchers.IO) {
        PdfNative.buildSearchIndex(handle)
    }

    /** Serialize this document encrypted with the given passwords, or null. */
    suspend fun saveEncrypted(userPw: String, ownerPw: String): ByteArray? =
        withContext(Dispatchers.IO) { PdfNative.saveEncrypted(handle, userPw, ownerPw) }

    companion object {
        private const val TAG = "SafePdfDocument"
        private const val MAX_CACHED_PAGES = 12
        /**
         * Rough per-primitive retained cost, in bytes. `FillPath` holds `List<List<Offset>>`
         * and `Offset` boxes inside a List, so a four-point fill is already ~176 bytes once
         * the object, both lists and the boxed points are counted; a short `Text` is similar.
         * An order-of-magnitude estimate, not a measurement.
         */
        private const val PRIMITIVE_WEIGHT_BYTES = 128L
        /**
         * Aggregate retained budget for [cache], in BYTES.
         *
         * This used to count only decoded-bitmap PIXELS, which was blind in both directions.
         * Bitmaps are four bytes per pixel, so a nominal "64 MP" cap actually permitted ~256 MB
         * — more than the whole heap on most devices, so it never fired. Worse, a vector page
         * contributed ZERO, so a document of dense drawings could hold twelve 300k-primitive
         * pages (~450 MB of Kotlin objects) with the budget reading zero and
         * [MAX_CACHED_PAGES] as the only bound. That is the more likely OOM of the two, and it
         * got three times worse when `MAX_CONTENT_OPS` was raised, making Rust's 300k
         * `MAX_PRIMITIVES` reachable where a page previously topped out near 100k.
         *
         * Evicting a page that is currently on screen is harmless: the composable holds its own
         * reference to the decoded page and `produceState` does not re-run, so eviction costs at
         * most a re-render if that page is requested again. The budget can therefore be tight.
         */
        private const val MAX_CACHED_BYTES = 96L * 1024 * 1024
        /**
         * Open [uri] as a safe PDF, or return `null` when the native lib is
         * unavailable, the bytes can't be read, or parsing fails. Encrypted PDFs
         * are opened with [password] (empty by default). Runs off the main thread.
         */
        suspend fun open(context: Context, uri: Uri, password: String? = null): SafePdfDocument? =
            withContext(Dispatchers.IO) {
                if (!PdfNative.isAvailable) return@withContext null
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: return@withContext null

                val handle = if (password == null) {
                    PdfNative.openDocument(bytes)
                } else {
                    PdfNative.openDocumentWithPassword(bytes, password)
                }
                if (handle == 0L) return@withContext null
                SafePdfDocument(handle, PdfNative.getPageCount(handle))
            }

        /** Encryption state of [uri]: 0 none, 1 needs password, 2 unsupported. */
        suspend fun passwordState(context: Context, uri: Uri): Int =
            withContext(Dispatchers.IO) {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: return@withContext 0
                PdfNative.pdfPasswordState(bytes)
            }

        /**
         * True if [uri] begins like a real PDF — a `%PDF-` header within the first 1024
         * bytes, as the spec requires readers to look for. Used to tell "not a PDF at all"
         * (e.g. a download that returned an HTML error/challenge page saved with a .pdf
         * name) apart from a genuine PDF the parser can't handle, so the error message can
         * be honest. Only the head is read, so it's cheap even for huge inputs.
         */
        suspend fun looksLikePdf(context: Context, uri: Uri): Boolean =
            withContext(Dispatchers.IO) {
                val head = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buf = ByteArray(1024)
                        var off = 0
                        while (off < buf.size) {
                            val r = input.read(buf, off, buf.size - off)
                            if (r < 0) break
                            off += r
                        }
                        buf.copyOf(off)
                    }
                }.getOrNull() ?: return@withContext false
                indexOf(head, "%PDF-".toByteArray(Charsets.US_ASCII)) >= 0
            }

        /** First index of [needle] in [haystack], or -1. */
        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }
    }
}
