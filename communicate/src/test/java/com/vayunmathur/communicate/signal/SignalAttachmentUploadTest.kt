package com.vayunmathur.communicate.signal

import com.vayunmathur.communicate.data.signal.transport.SignalAttachmentUpload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parser vectors for the `GET /v4/attachments/form/upload` response. */
class SignalAttachmentUploadTest {
    private fun parse(body: String) = SignalAttachmentUpload.parseForm(body, warn = {})

    @Test
    fun parsesAFullForm() {
        val form = parse(
            """
            {"cdn":3,"key":"abc123","signedUploadLocation":"https://cdn3.signal.org/upload/xyz",
             "headers":{"Authorization":"Bearer t","x-signal-thing":"v"}}
            """.trimIndent(),
        )
        assertEquals(3, form?.cdn)
        assertEquals("abc123", form?.key)
        assertEquals("https://cdn3.signal.org/upload/xyz", form?.signedUploadLocation)
        assertEquals("Bearer t", form?.headers?.get("Authorization"))
        assertEquals(2, form?.headers?.size)
    }

    @Test
    fun headersAreOptional() {
        val form = parse("""{"cdn":3,"key":"k","signedUploadLocation":"https://x/y"}""")
        assertTrue(form?.headers?.isEmpty() == true)
    }

    @Test
    fun rejectsFormsMissingRequiredFields() {
        assertNull(parse("""{"key":"k","signedUploadLocation":"https://x/y"}"""))
        assertNull(parse("""{"cdn":3,"signedUploadLocation":"https://x/y"}"""))
        assertNull(parse("""{"cdn":3,"key":"k"}"""))
        assertNull(parse("""{"cdn":3,"key":"","signedUploadLocation":"https://x/y"}"""))
    }

    @Test
    fun rejectsJunk() {
        assertNull(parse("not json"))
        assertNull(parse(""))
    }
}
