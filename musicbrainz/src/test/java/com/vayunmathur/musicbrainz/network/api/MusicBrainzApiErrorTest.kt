package com.vayunmathur.musicbrainz.network.api

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins how HTTP statuses from WS/2 map onto failures.
 *
 * Every non-2xx status is one kind of failure, 503 included: WS/2 answers it when a client
 * outruns the shared rate limit, and there is nothing extra for the user to do about that than
 * for any other fault.
 */
class MusicBrainzApiErrorTest {

    @Test
    fun `error statuses are failures`() {
        for (status in listOf(400, 401, 403, 404, 429, 500, 502, 503, 504)) {
            assertTrue(
                MusicBrainzApi.failureFor(status, "boom") is IOException,
                "$status should fail",
            )
        }
    }

    @Test
    fun `success statuses are not failures`() {
        for (status in listOf(200, 201, 204, 299)) {
            assertNull(MusicBrainzApi.failureFor(status, "{}"), "$status should not fail")
        }
    }

    /** The status is kept in the message so a report names the code that caused it. */
    @Test
    fun `carries the status into the message`() {
        assertEquals("HTTP 500: boom", MusicBrainzApi.failureFor(500, "boom")?.message)
        assertTrue(MusicBrainzApi.failureFor(503, "waiting")?.message?.contains("503") == true)
    }

    /** A long error body must not be pasted wholesale into a message the UI may show. */
    @Test
    fun `truncates a long body`() {
        val message = MusicBrainzApi.failureFor(500, "x".repeat(5_000))?.message.orEmpty()
        assertTrue(message.length < 600, "message was ${message.length} chars")
    }
}
