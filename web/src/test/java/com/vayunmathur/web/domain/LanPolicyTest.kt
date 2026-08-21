package com.vayunmathur.web.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanPolicyTest {

    /** Records every lookup so tests can assert the DNS path is not taken needlessly. */
    private class FakeResolver(
        private val answers: Map<String, List<String>> = emptyMap(),
        private val throwFor: Set<String> = emptySet(),
    ) : HostResolver {
        var calls = 0
            private set

        override fun resolve(host: String): List<String> {
            calls++
            if (host in throwFor) throw java.net.UnknownHostException(host)
            return answers[host] ?: emptyList()
        }
    }

    private class FakeClock(var millis: Long = 0L) : () -> Long {
        override fun invoke(): Long = millis
    }

    @Test
    fun `non-http schemes are allowed without a lookup`() {
        val resolver = FakeResolver()
        val policy = LanPolicy(resolver, FakeClock())
        assertTrue(policy.allowsCleartext("https://example.com/"))
        assertTrue(policy.allowsCleartext("about:blank"))
        assertTrue(policy.allowsCleartext("data:text/html,hi"))
        assertEquals(0, resolver.calls)
    }

    @Test
    fun `syntactically local hosts are allowed without a lookup`() {
        val resolver = FakeResolver()
        val policy = LanPolicy(resolver, FakeClock())
        assertTrue(policy.allowsCleartext("http://nas.local/"))
        assertTrue(policy.allowsCleartext("http://192.168.1.1/"))
        assertTrue(policy.allowsCleartext("http://router/"))
        assertTrue(policy.allowsCleartext("http://[::1]:3000/"))
        assertEquals(0, resolver.calls)
    }

    @Test
    fun `syntactically public hosts are blocked without a lookup`() {
        val resolver = FakeResolver()
        val policy = LanPolicy(resolver, FakeClock())
        assertFalse(policy.allowsCleartext("http://8.8.8.8/"))
        assertEquals(0, resolver.calls)
    }

    @Test
    fun `a private answer is allowed`() {
        val resolver = FakeResolver(mapOf("dev.example.com" to listOf("10.0.0.5")))
        val policy = LanPolicy(resolver, FakeClock())
        assertTrue(policy.allowsCleartext("http://dev.example.com/x"))
        assertEquals(1, resolver.calls)
    }

    @Test
    fun `a public answer is blocked`() {
        val resolver = FakeResolver(mapOf("example.com" to listOf("93.184.216.34")))
        val policy = LanPolicy(resolver, FakeClock())
        assertFalse(policy.allowsCleartext("http://example.com/"))
    }

    @Test
    fun `mixed answers are blocked`() {
        // The DNS-rebinding shape: any-of would be a front door.
        val resolver = FakeResolver(mapOf("evil.example.com" to listOf("10.0.0.5", "93.184.216.34")))
        val policy = LanPolicy(resolver, FakeClock())
        assertFalse(policy.allowsCleartext("http://evil.example.com/"))
    }

    @Test
    fun `an empty answer is blocked`() {
        val resolver = FakeResolver()
        val policy = LanPolicy(resolver, FakeClock())
        assertFalse(policy.allowsCleartext("http://gone.example.com/"))
    }

    @Test
    fun `a throwing resolver is blocked`() {
        val resolver = FakeResolver(throwFor = setOf("broken.example.com"))
        val policy = LanPolicy(resolver, FakeClock())
        assertFalse(policy.allowsCleartext("http://broken.example.com/"))
    }

    @Test
    fun `zone ids in resolved addresses are ignored`() {
        val resolver = FakeResolver(mapOf("link.example.com" to listOf("fe80:0:0:0:0:0:0:1%wlan0")))
        val policy = LanPolicy(resolver, FakeClock())
        assertTrue(policy.allowsCleartext("http://link.example.com/"))
    }

    @Test
    fun `a decided host is only resolved once`() {
        val resolver = FakeResolver(mapOf("dev.example.com" to listOf("10.0.0.5")))
        val policy = LanPolicy(resolver, FakeClock())
        assertTrue(policy.allowsCleartext("http://dev.example.com/a"))
        assertTrue(policy.allowsCleartext("http://dev.example.com/b"))
        assertEquals(1, resolver.calls)
    }

    @Test
    fun `the decision is re-resolved after its TTL`() {
        val clock = FakeClock()
        val resolver = FakeResolver(mapOf("dev.example.com" to listOf("10.0.0.5")))
        val policy = LanPolicy(resolver, clock)
        assertTrue(policy.allowsCleartext("http://dev.example.com/"))
        clock.millis += 5 * 60 * 1000L
        assertTrue(policy.allowsCleartext("http://dev.example.com/"))
        assertEquals(2, resolver.calls)
    }

    @Test
    fun `a failure is retried much sooner than a decision`() {
        val clock = FakeClock()
        val resolver = FakeResolver()
        val policy = LanPolicy(resolver, clock)
        assertFalse(policy.allowsCleartext("http://gone.example.com/"))
        clock.millis += 9_000L
        assertFalse(policy.allowsCleartext("http://gone.example.com/"))
        assertEquals(1, resolver.calls)
        clock.millis += 2_000L
        assertFalse(policy.allowsCleartext("http://gone.example.com/"))
        assertEquals(2, resolver.calls)
    }

    @Test
    fun `clearCache forces a fresh lookup`() {
        val resolver = FakeResolver(mapOf("dev.example.com" to listOf("10.0.0.5")))
        val policy = LanPolicy(resolver, FakeClock())
        assertTrue(policy.allowsCleartext("http://dev.example.com/"))
        policy.clearCache()
        assertTrue(policy.allowsCleartext("http://dev.example.com/"))
        assertEquals(2, resolver.calls)
    }

    @Test
    fun `the cache key is case insensitive`() {
        val resolver = FakeResolver(mapOf("dev.example.com" to listOf("10.0.0.5")))
        val policy = LanPolicy(resolver, FakeClock())
        assertTrue(policy.allowsCleartext("http://DEV.Example.com/"))
        assertTrue(policy.allowsCleartext("http://dev.example.com/"))
        assertEquals(1, resolver.calls)
    }
}
