package com.vayunmathur.web.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Only the navigation paths are exercised here. The search fallback goes through
 * `Uri.encode`, which is an unimplemented stub in a JVM unit test, so the "this is a search"
 * cases are asserted through [BrowserUtils.looksLikeUrl] instead.
 */
class BrowserUtilsTest {

    @Test
    fun `LAN-looking input navigates over http`() {
        assertEquals("http://nas.local", BrowserUtils.toNavigationUrl("nas.local"))
        assertEquals("http://192.168.1.1:8080", BrowserUtils.toNavigationUrl("192.168.1.1:8080"))
        assertEquals("http://localhost:3000", BrowserUtils.toNavigationUrl("localhost:3000"))
    }

    @Test
    fun `public input navigates over https`() {
        assertEquals("https://example.com", BrowserUtils.toNavigationUrl("example.com"))
        assertEquals("https://example.com/a?b=1", BrowserUtils.toNavigationUrl("example.com/a?b=1"))
    }

    @Test
    fun `an explicit scheme is kept verbatim`() {
        assertEquals("http://router", BrowserUtils.toNavigationUrl("http://router"))
        assertEquals("http://example.com/", BrowserUtils.toNavigationUrl("http://example.com/"))
    }

    @Test
    fun `input that is not URL-like falls through to search`() {
        assertFalse(BrowserUtils.looksLikeUrl("router"))
        assertFalse(BrowserUtils.looksLikeUrl("hello world"))
        assertTrue(BrowserUtils.looksLikeUrl("nas.local"))
        assertTrue(BrowserUtils.looksLikeUrl("192.168.1.1"))
    }
}
