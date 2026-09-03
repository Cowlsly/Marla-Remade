package com.vayunmathur.translate.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentLanguagesTest {

    @Test
    fun `pushing a new code moves it to the front`() {
        assertEquals(
            listOf("fr", "es", "de"),
            pushRecentLanguage(listOf("es", "de"), "fr"),
        )
    }

    @Test
    fun `pushing an existing code dedupes instead of duplicating`() {
        assertEquals(
            listOf("es", "de"),
            pushRecentLanguage(listOf("de", "es"), "es"),
        )
    }

    @Test
    fun `recents are capped at the maximum`() {
        val full = listOf("a", "b", "c", "d", "e", "f")
        assertEquals(MAX_RECENT_LANGUAGES, full.size)
        val updated = pushRecentLanguage(full, "g")
        assertEquals(MAX_RECENT_LANGUAGES, updated.size)
        assertEquals("g", updated.first())
        assertTrue("f" !in updated, "oldest entry is evicted")
    }

    @Test
    fun `auto is never stored as a recent`() {
        assertEquals(
            listOf("es"),
            pushRecentLanguage(listOf("es"), Languages.AUTO.code),
        )
        assertEquals(emptyList(), pushRecentLanguage(emptyList(), Languages.AUTO.code))
    }

    @Test
    fun `blank and empty join strings parse to nothing`() {
        assertEquals(emptyList(), parseRecentLanguages(null))
        assertEquals(emptyList(), parseRecentLanguages(""))
        assertEquals(emptyList(), parseRecentLanguages("  "))
    }

    @Test
    fun `join strings round-trip through the parser`() {
        assertEquals(listOf("es", "fr"), parseRecentLanguages("es,fr"))
        assertEquals(listOf("es", "fr"), parseRecentLanguages("es, fr,"))
    }
}
