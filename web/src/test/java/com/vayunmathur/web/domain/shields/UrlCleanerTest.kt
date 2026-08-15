package com.vayunmathur.web.domain.shields

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class UrlCleanerTest {

    @Test
    fun `strips known tracking parameters`() {
        assertEquals(
            "https://example.com/page?id=7",
            UrlCleaner.clean("https://example.com/page?utm_source=news&id=7&fbclid=abc"),
        )
    }

    @Test
    fun `drops the question mark when nothing survives`() {
        assertEquals(
            "https://example.com/page",
            UrlCleaner.clean("https://example.com/page?utm_medium=email&gclid=1"),
        )
    }

    @Test
    fun `keeps the fragment`() {
        assertEquals(
            "https://example.com/p?q=1#section",
            UrlCleaner.clean("https://example.com/p?q=1&utm_campaign=x#section"),
        )
    }

    @Test
    fun `preserves order and repeated keys`() {
        assertEquals(
            "https://example.com/?b=2&a=1&a=3",
            UrlCleaner.clean("https://example.com/?b=2&a=1&utm_term=z&a=3"),
        )
    }

    @Test
    fun `returns the same instance when there is nothing to strip`() {
        val url = "https://example.com/page?id=7&q=hello"
        assertSame(url, UrlCleaner.clean(url))
        assertSame("https://example.com/page", UrlCleaner.clean("https://example.com/page"))
    }

    @Test
    fun `matches parameter names case insensitively`() {
        assertEquals("https://example.com/?a=1", UrlCleaner.clean("https://example.com/?a=1&UTM_Source=x&FBCLID=y"))
    }

    @Test
    fun `does not strip parameters that merely contain a tracking name`() {
        val url = "https://example.com/?custom_utm_source=1&fbclid_backup=2"
        assertSame(url, UrlCleaner.clean(url))
    }

    @Test
    fun `upgrades plain http`() {
        assertEquals("https://example.com/a?b=1", UrlCleaner.httpsUpgrade("http://example.com/a?b=1"))
    }

    @Test
    fun `leaves https and non-http schemes alone`() {
        assertNull(UrlCleaner.httpsUpgrade("https://example.com"))
        assertNull(UrlCleaner.httpsUpgrade("about:blank"))
        assertNull(UrlCleaner.httpsUpgrade("data:text/html,hi"))
    }

    @Test
    fun `does not upgrade loopback or bare IPs`() {
        assertNull(UrlCleaner.httpsUpgrade("http://localhost:8080/dev"))
        assertNull(UrlCleaner.httpsUpgrade("http://dev.localhost/x"))
        assertNull(UrlCleaner.httpsUpgrade("http://192.168.1.4:3000"))
        assertNull(UrlCleaner.httpsUpgrade("http://[::1]:9000"))
    }

    @Test
    fun `upgrades hosts that only look numeric`() {
        assertEquals("https://1.2.3.4.example.com/", UrlCleaner.httpsUpgrade("http://1.2.3.4.example.com/"))
    }
}
