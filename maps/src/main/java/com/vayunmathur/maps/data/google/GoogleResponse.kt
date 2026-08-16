package com.vayunmathur.maps.data.google

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Google's map XHR responses begin with an XSSI guard (`)]}'` then a newline) and
 * then a deeply nested *positional* JSON array — no field names anywhere. This
 * object strips the guard and parses; the extension functions below give
 * null-safe positional access so a missing/reordered index returns null instead
 * of throwing.
 *
 * That defensiveness is the whole game: this is a keyless scrape of a private,
 * undocumented endpoint. Google inserts elements mid-array without notice and a
 * hard-coded `[0][1][3]` path silently rots — so every access degrades to null
 * and the enrichment feature just disappears rather than crashing the sheet.
 *
 * Ported from Vela's GoogleResponse (calibrated against maps.google.com 2026-06).
 */
internal object GoogleResponse {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val GUARDS = listOf(")]}'\n", ")]}',\n", ")]}'", ")]}", "while(1);", "for(;;);")

    fun strip(body: String): String {
        for (g in GUARDS) if (body.startsWith(g)) return body.substring(g.length).trimStart('\n', '\r')
        return body
    }

    /** Strip the guard and parse to a JSON tree. Returns null on any malformed body. */
    fun parseOrNull(body: String): JsonElement? =
        runCatching { json.parseToJsonElement(strip(body)) }.getOrNull()
}

/** Walk a positional path; any wrong/missing step yields null rather than throwing. */
internal fun JsonElement?.at(vararg path: Int): JsonElement? {
    var cur: JsonElement? = this
    for (i in path) {
        val arr = cur as? JsonArray ?: return null
        cur = arr.getOrNull(i)
    }
    return cur
}

internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
internal fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.doubleOrNull
internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
internal fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
