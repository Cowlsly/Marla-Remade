package com.vayunmathur.code.util

import com.vayunmathur.code.syntax.Language

/** A tree-sitter capture kind, aligned 1:1 (by ordinal) with the Rust side's enum. */
enum class TsKind { KEYWORD, STRING, NUMBER, COMMENT, ANNOTATION, FUNCTION, TYPE, PROPERTY }

/** A highlighted span `[start, end)` (character offsets into the source) of a given [kind]. */
data class TsSpan(val start: Int, val end: Int, val kind: TsKind)

/**
 * JNI bridge to the native tree-sitter highlighter (`libcode_ts.so`, built from
 * `code/src/main/rust/`). Loads the library once; [isAvailable] is false when the native lib is
 * missing for the current ABI (e.g. the reproducible `.so` was not built), so callers fall back to
 * the regex tokenizer instead of crashing. This mirrors the `PdfNative` graceful-degradation pattern.
 *
 * [highlight] is blocking (it parses the whole file), so callers must run it off the main thread and
 * cache the result — see `rememberTreeSitterSpans` in the editors.
 */
object TreeSitterNative {

    val isAvailable: Boolean =
        try {
            System.loadLibrary("code_ts")
            true
        } catch (t: Throwable) {
            false
        }

    /**
     * Highlights [source] for [languageId] (see [languageIdFor]), returning packed
     * `(startByte, endByte, captureKindOrdinal)` triples, or null on failure / unsupported language.
     */
    external fun highlight(languageId: String, source: String): IntArray?

    private val kinds = TsKind.entries

    /** The Rust-side language id for [language], or null when tree-sitter has no grammar for it. */
    fun languageIdFor(language: Language): String? = when (language) {
        Language.KOTLIN -> "kotlin"
        Language.JAVA -> "java"
        Language.JAVASCRIPT -> "javascript"
        Language.TYPESCRIPT -> "typescript"
        Language.PYTHON -> "python"
        Language.RUST -> "rust"
        Language.GO -> "go"
        Language.JSON -> "json"
        Language.C -> "c"
        Language.CPP -> "cpp"
        else -> null
    }

    /**
     * Structure-aware spans for [text] in [language], or null when tree-sitter is unavailable, the
     * language is unsupported, or the native call fails — signalling the caller to use the regex path.
     */
    fun spans(text: String, language: Language): List<TsSpan>? {
        if (!isAvailable) return null
        val id = languageIdFor(language) ?: return null
        val packed = runCatching { highlight(id, text) }.getOrNull() ?: return null
        val out = ArrayList<TsSpan>(packed.size / 3)
        var i = 0
        while (i + 2 < packed.size) {
            val start = packed[i]
            val end = packed[i + 1]
            val kindOrdinal = packed[i + 2]
            val kind = kinds.getOrNull(kindOrdinal)
            if (kind != null && end > start) out.add(TsSpan(start, end, kind))
            i += 3
        }
        return out
    }
}
