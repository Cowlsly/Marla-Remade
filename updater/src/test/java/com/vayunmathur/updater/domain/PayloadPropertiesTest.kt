package com.vayunmathur.updater.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * These run immediately before a payload is written to a system partition, and the rejection
 * cases matter more than the happy one: a missing hash means update_engine cannot check what it
 * is about to write.
 */
class PayloadPropertiesTest {

    private val complete = listOf(
        "FILE_HASH=0Z1uPCUyPO2gJfL/9ZVjKZeJDIw3TvIC3wJhZWpZ5cU=",
        "FILE_SIZE=1509949440",
        "METADATA_HASH=Yqp4GRZLB4bZWXKQCqEHRJXQxLHhLmVYAmVv0Sd2vJk=",
        "METADATA_SIZE=68235",
    )

    @Test
    fun `a complete properties file parses to its own lines`() {
        val result = PayloadProperties.parse(complete.asSequence())
        assertEquals(complete, assertIs<PayloadProperties.Result.Parsed>(result).headers)
    }

    @Test
    fun `base64 values containing padding survive intact`() {
        // The values are base64 and end in '='. A naive split on '=' would truncate them, and
        // update_engine would reject a hash that is silently missing its last characters.
        val result = PayloadProperties.parse(complete.asSequence())
        assertTrue(
            assertIs<PayloadProperties.Result.Parsed>(result)
                .headers
                .first()
                .endsWith("wJhZWpZ5cU="),
        )
    }

    @Test
    fun `blank lines and surrounding whitespace are ignored`() {
        val result = PayloadProperties.parse(
            sequenceOf("", "  FILE_HASH=a  ", "FILE_SIZE=1", "", "METADATA_HASH=b", "METADATA_SIZE=2", "   "),
        )
        assertEquals(
            listOf("FILE_HASH=a", "FILE_SIZE=1", "METADATA_HASH=b", "METADATA_SIZE=2"),
            assertIs<PayloadProperties.Result.Parsed>(result).headers,
        )
    }

    @Test
    fun `a missing required key is rejected and named`() {
        val result = PayloadProperties.parse(complete.dropLast(1).asSequence())
        assertTrue(
            assertIs<PayloadProperties.Result.Rejected>(result).reason.contains("METADATA_SIZE"),
        )
    }

    @Test
    fun `every missing key is named, not just the first`() {
        val result = PayloadProperties.parse(sequenceOf("FILE_HASH=a", "FILE_SIZE=1"))
        val reason = assertIs<PayloadProperties.Result.Rejected>(result).reason
        assertTrue(reason.contains("METADATA_HASH"), reason)
        assertTrue(reason.contains("METADATA_SIZE"), reason)
    }

    @Test
    fun `a line with no separator is rejected rather than skipped`() {
        // Skipping it would drop a header update_engine needed and leave the reason for the
        // eventual failure a hundred lines away, inside update_engine.
        val result = PayloadProperties.parse(complete.asSequence() + sequenceOf("GARBAGE"))
        assertTrue(assertIs<PayloadProperties.Result.Rejected>(result).reason.contains("GARBAGE"))
    }

    @Test
    fun `a line with an empty key is rejected`() {
        val result = PayloadProperties.parse(complete.asSequence() + sequenceOf("=value"))
        assertIs<PayloadProperties.Result.Rejected>(result)
    }

    @Test
    fun `an empty file is rejected`() {
        assertIs<PayloadProperties.Result.Rejected>(PayloadProperties.parse(emptySequence()))
    }

    @Test
    fun `unknown extra keys are passed through rather than dropped`() {
        // update_engine reads more properties than the four we insist on; forwarding what we do
        // not recognise keeps this from having to track its full list.
        val result = PayloadProperties.parse(complete.asSequence() + sequenceOf("POWERWASH=0"))
        assertTrue(assertIs<PayloadProperties.Result.Parsed>(result).headers.contains("POWERWASH=0"))
    }
}
