package com.vayunmathur.musicbrainz.network.api

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins how HTTP statuses from the self-hosted catalogue map onto failures.
 *
 * This is the contract with the server written down as assertions: 503 means "no catalogue
 * loaded yet", which is a wait, and 500 means a genuine fault. The two never overlap, which is
 * what lets the app say "catalogue not ready" instead of "something went wrong". If the server
 * ever starts answering 503 for real faults, or 500 while it is still importing, the screens
 * quietly start lying to the user - so the split is asserted here rather than trusted.
 */
class MusicBrainzApiErrorTest {

    @Test
    fun `503 is a wait rather than a fault`() {
        val failure = MusicBrainzApi.failureFor(
            503,
            """{"error":"not_ready","state":"absent","detail":"no catalogue pack on this server"}""",
        )
        assertTrue(
            failure is CatalogueNotReadyException,
            "503 must surface as not-ready, got ${failure?.javaClass?.simpleName}",
        )
    }

    /**
     * An absent catalogue is not always on its way - the host may not be able to build one at
     * all - so the server's explanation is carried through to replace copy that would otherwise
     * promise it is coming shortly.
     */
    @Test
    fun `carries the reason an absent catalogue is not coming`() {
        val failure = MusicBrainzApi.failureFor(
            503,
            """{"error":"not_ready","state":"absent","detail":"cannot build the catalogue here: 5.2 GB RAM free, a build needs 8.0 GB"}""",
        ) as CatalogueNotReadyException
        assertEquals(CatalogueNotReadyException.ABSENT, failure.state)
        assertEquals(
            "cannot build the catalogue here: 5.2 GB RAM free, a build needs 8.0 GB",
            failure.reason,
        )
    }

    /**
     * A build in progress genuinely does resolve on its own, so it carries NO reason - the
     * screen keeps its own "still being prepared, try again shortly" copy, which is true here
     * and would be a false promise for an absent catalogue. `detail` is build-stage jargon
     * ("spilling tables") and is not shown to anyone.
     */
    @Test
    fun `does not carry a reason while building`() {
        val failure = MusicBrainzApi.failureFor(
            503,
            """{"error":"not_ready","state":"building","progress":0.42,"detail":"spilling tables"}""",
        ) as CatalogueNotReadyException
        assertEquals(CatalogueNotReadyException.BUILDING, failure.state)
        assertNull(failure.reason, "a build in progress must not present itself as a dead end")
    }

    /** A 503 whose body is not the documented JSON still has to read as not-ready. */
    @Test
    fun `survives a 503 body that is not the documented json`() {
        for (body in listOf("", "Service Unavailable", "<html>502 upstream</html>", "{")) {
            val failure = MusicBrainzApi.failureFor(503, body)
            assertTrue(failure is CatalogueNotReadyException, "body ${'"'}$body${'"'} should be not-ready")
            assertNull(failure.reason, "an unparseable body cannot supply a reason")
            assertTrue(failure.retryable, "with nothing to go on, retrying is worth offering")
        }
    }

    /**
     * `absent` is NOT the same as "never". A build that failed and is queued for the next check,
     * and a first boot before the scheduler has run, are both absent yet worth retrying - so the
     * server reports retryability and the app must not infer it from the state or from the
     * presence of a reason.
     */
    @Test
    fun `an absent catalogue that is still coming stays retryable`() {
        val failure = MusicBrainzApi.failureFor(
            503,
            """{"error":"not_ready","state":"absent","retryable":true,"detail":"a build failed and is queued for the next check"}""",
        ) as CatalogueNotReadyException
        assertTrue(failure.retryable, "a queued rebuild must keep the retry button")
        assertEquals("a build failed and is queued for the next check", failure.reason)
    }

    /** The case where a retry can never work, so the button must not be offered. */
    @Test
    fun `a host that cannot build is not retryable`() {
        val failure = MusicBrainzApi.failureFor(
            503,
            """{"error":"not_ready","state":"absent","retryable":false,"detail":"cannot build the catalogue here"}""",
        ) as CatalogueNotReadyException
        assertFalse(failure.retryable)
        assertEquals("cannot build the catalogue here", failure.reason)
    }

    /**
     * If the server ever stops sending `retryable`, fall back to inferring it from the reason
     * rather than offering a retry that cannot work: a body with a reason behaves as it did
     * before the field existed, and one without stays retryable.
     */
    @Test
    fun `infers retryability when the server does not report it`() {
        val withReason = MusicBrainzApi.failureFor(
            503,
            """{"error":"not_ready","state":"absent","detail":"cannot build the catalogue here"}""",
        ) as CatalogueNotReadyException
        assertFalse(withReason.retryable, "a reason with no retryable field must not offer a retry")

        val building = MusicBrainzApi.failureFor(
            503,
            """{"error":"not_ready","state":"building","progress":0.1}""",
        ) as CatalogueNotReadyException
        assertTrue(building.retryable)
    }

    /**
     * `progress` is ABSENT until it is known, not `0.0`. Decoding it as zero would report a build
     * that has not started as one that has started and made no headway - and if it were ever
     * shown as a percentage, a build about to finish could read 0% on the first poll.
     */
    @Test
    fun `absent progress is unknown rather than zero`() {
        val unknown = MusicBrainzApi.json.decodeFromString<NotReadyBody>(
            """{"error":"not_ready","state":"building","retryable":true}""",
        )
        assertNull(unknown.progress, "a missing progress must not decode as 0.0")

        val known = MusicBrainzApi.json.decodeFromString<NotReadyBody>(
            """{"error":"not_ready","state":"building","retryable":true,"progress":0.42}""",
        )
        assertEquals(0.42f, known.progress)

        // Zero sent explicitly is a real zero, and must stay distinguishable from absent.
        val zero = MusicBrainzApi.json.decodeFromString<NotReadyBody>(
            """{"error":"not_ready","state":"building","retryable":true,"progress":0.0}""",
        )
        assertEquals(0f, zero.progress)
    }

    /** `retryable` is always sent, so it decodes straight through in both directions. */
    @Test
    fun `retryable decodes from the status probe shape`() {
        assertEquals(
            true,
            MusicBrainzApi.json.decodeFromString<NotReadyBody>(
                """{"state":"absent","retryable":true}""",
            ).retryable,
        )
        assertEquals(
            false,
            MusicBrainzApi.json.decodeFromString<NotReadyBody>(
                """{"state":"absent","retryable":false}""",
            ).retryable,
        )
    }

    /**
     * The server's own pinned wire format, pasted verbatim.
     *
     * These three strings are asserted byte-for-byte on the server side (`9993b08`), so copying
     * them here rather than paraphrasing puts both ends of the contract on the same literals: if
     * the server's format drifts its test fails, and if this client's parsing drifts this one
     * does. That matters more than usual because production still answers 404, so no integration
     * test can catch a mismatch between us yet.
     */
    @Test
    fun `parses the server's pinned bodies verbatim`() {
        val builderDisabled = MusicBrainzApi.failureFor(
            503,
            """{"error":"not_ready","state":"absent","retryable":false,"detail":"the offline catalogue builder is not enabled on this server yet"}""",
        ) as CatalogueNotReadyException
        assertEquals(CatalogueNotReadyException.ABSENT, builderDisabled.state)
        assertFalse(builderDisabled.retryable, "a disabled builder cannot be waited out")
        assertEquals(
            "the offline catalogue builder is not enabled on this server yet",
            builderDisabled.reason,
        )

        val building = MusicBrainzApi.failureFor(
            503,
            """{"error":"not_ready","state":"building","retryable":true,"progress":0.42,"detail":"spilling tables"}""",
        ) as CatalogueNotReadyException
        assertEquals(CatalogueNotReadyException.BUILDING, building.state)
        assertTrue(building.retryable)
        // Build-stage jargon is never shown, so it is deliberately not carried as a reason.
        assertNull(building.reason)

        // The COMPLETE status body: no `error`, no `detail`. It has to decode all the same.
        val status = MusicBrainzApi.json.decodeFromString<NotReadyBody>(
            """{"state":"absent","retryable":true}""",
        )
        assertEquals("absent", status.state)
        assertEquals(true, status.retryable)
        assertNull(status.detail)
        assertNull(status.progress)
        assertEquals("", status.error)
    }

    /** Mid-build answers 503 too, and reads the same to the user: come back shortly. */
    @Test
    fun `503 while building is also a wait`() {
        val failure = MusicBrainzApi.failureFor(
            503,
            """{"error":"not_ready","state":"building","progress":0.42,"detail":"spilling tables"}""",
        )
        assertTrue(failure is CatalogueNotReadyException)
    }

    /**
     * 500 is the server's signal for a real fault and must NOT be dressed up as a wait -
     * telling the user to try again in a few minutes would hide a genuine server bug.
     */
    @Test
    fun `500 is a genuine failure`() {
        val failure = MusicBrainzApi.failureFor(500, """{"error":"internal"}""")
        assertTrue(failure is IOException)
        assertTrue(
            failure !is CatalogueNotReadyException,
            "500 must not be reported to the user as a catalogue that is still importing",
        )
    }

    /**
     * A 404 means the route is not there at all, which is a deployment fault rather than a
     * catalogue still importing. Reporting it as not-ready would reassure the user while
     * hiding that the server was never rolled out.
     */
    @Test
    fun `404 is a genuine failure, not a missing catalogue`() {
        val failure = MusicBrainzApi.failureFor(404, "Not Found")
        assertTrue(failure is IOException)
        assertTrue(failure !is CatalogueNotReadyException)
    }

    @Test
    fun `other error statuses are genuine failures`() {
        for (status in listOf(400, 401, 403, 429, 502, 504)) {
            val failure = MusicBrainzApi.failureFor(status, "boom")
            assertTrue(failure is IOException, "$status should fail")
            assertTrue(failure !is CatalogueNotReadyException, "$status must not read as not-ready")
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
