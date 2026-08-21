package com.vayunmathur.web.domain

import java.util.concurrent.ConcurrentHashMap

/**
 * Decides whether a cleartext request is allowed, i.e. whether its host is on the LAN.
 *
 * The platform's `cleartextTrafficPermitted` flag is open so that LAN sites (routers, NAS
 * boxes, dev servers) can load at all; this is what keeps public `http://` blocked. Enforcement
 * has to live here rather than in `network_security_config.xml` because that file matches only
 * exact hostnames and a browser navigates wherever the user types.
 *
 * DNS is the rare path: `https://` skips it entirely, and IP literals, `localhost`, `*.local`
 * and dotless hosts are answered by [LocalNetwork] without a lookup. Resolution failures block,
 * which is never a regression — before this gate existed, *all* cleartext was blocked.
 *
 * Safe to call from the WebView background thread; that thread may block, which is why
 * [HostResolver] is expected to impose its own timeout.
 */
class LanPolicy(
    private val resolver: HostResolver,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private class Entry(val lan: Boolean, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** True when [url] may be fetched. Only cleartext is ever refused. */
    fun allowsCleartext(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true)) return true
        return isLan(LocalNetwork.hostOf(url))
    }

    fun isLan(host: String): Boolean = when (LocalNetwork.classify(host)) {
        HostKind.LAN -> true
        HostKind.PUBLIC -> false
        HostKind.NEEDS_DNS -> resolvedIsLan(host.lowercase())
    }

    fun clearCache() = cache.clear()

    /**
     * **Every** resolved address must be on the LAN. A host answering with both a private and
     * a public address is exactly the DNS-rebinding shape, so accepting any-of would be a front
     * door. An empty answer decides nothing and therefore blocks.
     */
    private fun resolvedIsLan(host: String): Boolean {
        val at = now()
        cache[host]?.let { if (it.expiresAt > at) return it.lan }

        val addresses = runCatching { resolver.resolve(host) }.getOrDefault(emptyList())
        val decided = addresses.isNotEmpty()
        val lan = decided && addresses.all {
            LocalNetwork.classify(it.substringBefore('%').lowercase()) == HostKind.LAN
        }

        // A clear-at-capacity rather than a real LRU: `android.util.LruCache` would drag a
        // platform dependency into this module and break its JVM tests.
        if (cache.size >= MAX_ENTRIES) cache.clear()
        cache[host] = Entry(lan, at + if (decided) DECIDED_TTL_MS else FAILURE_TTL_MS)
        return lan
    }

    private companion object {
        const val MAX_ENTRIES = 256
        const val DECIDED_TTL_MS = 5 * 60 * 1000L

        /** Short, so one DNS hiccup does not pin a host into "blocked" for five minutes. */
        const val FAILURE_TTL_MS = 10 * 1000L
    }
}
