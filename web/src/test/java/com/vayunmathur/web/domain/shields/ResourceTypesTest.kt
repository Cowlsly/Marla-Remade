package com.vayunmathur.web.domain.shields

import kotlin.test.Test
import kotlin.test.assertEquals

class ResourceTypesTest {

    private fun type(
        url: String,
        isMainFrame: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ) = ResourceTypes.of(url, isMainFrame, headers)

    @Test
    fun `main frame is always a document`() {
        assertEquals("document", type("https://example.com/x.js", isMainFrame = true))
    }

    @Test
    fun `Sec-Fetch-Dest wins over the extension`() {
        assertEquals(
            "script",
            type("https://example.com/data.png", headers = mapOf("Sec-Fetch-Dest" to "script")),
        )
    }

    @Test
    fun `Sec-Fetch-Dest is matched case insensitively on both name and value`() {
        assertEquals("image", type("https://example.com/a", headers = mapOf("sec-fetch-dest" to "IMAGE")))
    }

    @Test
    fun `empty destination means xhr or fetch`() {
        assertEquals(
            "xmlhttprequest",
            type("https://example.com/api", headers = mapOf("Sec-Fetch-Dest" to "empty")),
        )
    }

    @Test
    fun `iframes are subdocuments`() {
        assertEquals("subdocument", type("https://ads.example/f", headers = mapOf("Sec-Fetch-Dest" to "iframe")))
    }

    @Test
    fun `falls back to the Accept header`() {
        assertEquals("stylesheet", type("https://example.com/s", headers = mapOf("Accept" to "text/css,*/*;q=0.1")))
        assertEquals("image", type("https://example.com/i", headers = mapOf("Accept" to "image/avif,image/webp")))
    }

    @Test
    fun `a generic html Accept does not masquerade as a subdocument`() {
        assertEquals(
            ResourceTypes.OTHER,
            type("https://example.com/thing", headers = mapOf("Accept" to "text/html,application/xhtml+xml")),
        )
    }

    @Test
    fun `falls back to the file extension`() {
        assertEquals("script", type("https://example.com/app.js"))
        assertEquals("stylesheet", type("https://example.com/a/b.css"))
        assertEquals("font", type("https://example.com/f.woff2"))
        assertEquals("media", type("https://example.com/v.mp4"))
        assertEquals("image", type("https://example.com/logo.SVG"))
    }

    @Test
    fun `query strings and fragments do not confuse the extension`() {
        assertEquals("script", type("https://example.com/app.js?v=3#x"))
        assertEquals(ResourceTypes.OTHER, type("https://example.com/path?file=a.js"))
    }

    @Test
    fun `extensionless paths fall through to other`() {
        assertEquals(ResourceTypes.OTHER, type("https://example.com/api/v1/users"))
        assertEquals(ResourceTypes.OTHER, type("https://example.com/"))
    }

    @Test
    fun `a dot in a directory name is not an extension`() {
        assertEquals(ResourceTypes.OTHER, type("https://example.com/v1.2/resource"))
    }
}
