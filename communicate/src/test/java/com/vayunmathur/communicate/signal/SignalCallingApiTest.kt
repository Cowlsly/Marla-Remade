package com.vayunmathur.communicate.signal

import com.vayunmathur.communicate.data.signal.transport.SignalCallingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parser vectors for `GET /v2/calling/relays`. A relay without usable urls has to be dropped rather than
 * turned into an ICE server that fails at connection time.
 */
class SignalCallingApiTest {
    private fun parse(body: String) = SignalCallingApi.parseRelays(body, warn = {})

    @Test
    fun parsesCredentialsAndUrls() {
        val servers = parse(
            """
            {"relays":[{"username":"u","password":"p","hostname":"turn.example",
             "urls":["turn:turn.example:80"],"urlsWithIps":["turn:1.2.3.4:80"],"ttl":86400}]}
            """.trimIndent(),
        )
        assertEquals(1, servers.size)
        assertEquals("u", servers[0].username)
        assertEquals("p", servers[0].password)
    }

    @Test
    fun prefersUrlsWithIpsToAvoidADnsLookupDuringSetup() {
        val servers = parse(
            """{"relays":[{"username":"u","password":"p","urls":["turn:host:80"],"urlsWithIps":["turn:9.9.9.9:80"]}]}""",
        )
        assertTrue(servers[0].urls.any { it.contains("9.9.9.9") })
    }

    @Test
    fun fallsBackToUrlsWhenNoIpsAreGiven() {
        val servers = parse("""{"relays":[{"username":"u","password":"p","urls":["turn:host:80"]}]}""")
        assertTrue(servers[0].urls.any { it.contains("host") })
    }

    @Test
    fun dropsRelaysWithNoUrls() {
        assertTrue(parse("""{"relays":[{"username":"u","password":"p"}]}""").isEmpty())
        assertTrue(parse("""{"relays":[{"username":"u","password":"p","urls":[]}]}""").isEmpty())
    }

    @Test
    fun handlesMultipleRelays() {
        val servers = parse(
            """
            {"relays":[{"username":"a","password":"b","urls":["turn:one:80"]},
                       {"username":"c","password":"d","urls":["turn:two:80"]}]}
            """.trimIndent(),
        )
        assertEquals(2, servers.size)
    }

    @Test
    fun emptyOrJunkGivesNoServers() {
        assertTrue(parse("""{"relays":[]}""").isEmpty())
        assertTrue(parse("""{}""").isEmpty())
        assertTrue(parse("not json").isEmpty())
        assertTrue(parse("").isEmpty())
    }
}
