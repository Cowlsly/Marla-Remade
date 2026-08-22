package com.vayunmathur.communicate.signal

import com.vayunmathur.communicate.data.signal.transport.SignalCdsi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Parser vectors for `GET /v2/directory/auth`. These are enclave credentials, distinct from the
 * account's own auth, so a partial response must be rejected rather than used to attempt a lookup.
 */
class SignalCdsiTest {
    private fun parse(body: String) = SignalCdsi.parseCredentials(body, warn = {})

    @Test
    fun parsesCredentials() {
        val creds = parse("""{"username":"u1","password":"p1"}""")
        assertEquals("u1", creds?.username)
        assertEquals("p1", creds?.password)
    }

    @Test
    fun ignoresUnknownFields() {
        val creds = parse("""{"username":"u1","password":"p1","extra":123}""")
        assertEquals("u1", creds?.username)
    }

    @Test
    fun rejectsPartialOrEmptyCredentials() {
        assertNull(parse("""{"username":"u1"}"""))
        assertNull(parse("""{"password":"p1"}"""))
        assertNull(parse("""{"username":"","password":"p1"}"""))
        assertNull(parse("""{"username":"u1","password":""}"""))
        assertNull(parse("""{}"""))
    }

    @Test
    fun rejectsJunk() {
        assertNull(parse("not json"))
        assertNull(parse(""))
    }
}
