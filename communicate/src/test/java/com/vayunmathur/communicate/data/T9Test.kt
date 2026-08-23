package com.vayunmathur.communicate.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vectors for dialpad T9 matching. Pure JVM — [T9] deliberately has no `android.*` dependency.
 */
class T9Test {

    private fun matches(name: String, phone: String, typed: String): Boolean =
        T9.matches(T9.entryFor(name, phone), T9.normalizeQuery(typed))

    @Test
    fun `maps letters to their ITU keys`() {
        assertEquals("22233344455566677778889999", T9.keysFor("abcdefghijklmnopqrstuvwxyz"))
        assertEquals('7', T9.digitFor('S'))
        assertEquals('9', T9.digitFor('Z'))
        assertEquals('0', T9.digitFor('0'))
    }

    @Test
    fun `drops characters with no keypad marking`() {
        assertNull(T9.digitFor('#'))
        assertNull(T9.digitFor('李'))
        assertEquals("", T9.keysFor("李小龍"))
        assertEquals("46", T9.keysFor("h. m."))
    }

    @Test
    fun `folds accents onto the printed letter`() {
        assertEquals("5673", T9.keysFor("José"))
        assertTrue(matches("José", "5551234", "5673"))
        assertTrue(matches("Ångström", "5551234", "2"))
    }

    @Test
    fun `matches any word of a multi-word name`() {
        assertTrue(matches("Robert Smith", "5551234", "76"))
        assertTrue(matches("Robert Smith", "5551234", "762"))
        assertTrue(matches("Mary-Jane Watson", "5551234", "5"))
        assertFalse(matches("Robert Smith", "5551234", "999"))
    }

    @Test
    fun `matches the run of initials`() {
        assertTrue(matches("Robert James Smith", "5551234", "757"))
        assertTrue(matches("Robert James Smith", "5551234", "75"))
        assertFalse(matches("Robert James Smith", "5551234", "758"))
    }

    @Test
    fun `matches digits anywhere in the phone number`() {
        assertTrue(matches("李小龍", "+1 (555) 867-5309", "8675"))
        assertTrue(matches("李小龍", "+1 (555) 867-5309", "555"))
        assertFalse(matches("李小龍", "+1 (555) 867-5309", "4321"))
    }

    @Test
    fun `ignores dial-only characters in the query`() {
        assertEquals("", T9.normalizeQuery("*#+"))
        assertEquals("76", T9.normalizeQuery("*7#6+"))
        assertTrue(matches("Robert Smith", "5551234", "*#+"))
        assertTrue(matches("Robert Smith", "5551234", "*76#"))
    }

    @Test
    fun `an empty query matches everything`() {
        assertTrue(matches("Robert Smith", "5551234", ""))
        assertTrue(matches("", "", ""))
    }
}
