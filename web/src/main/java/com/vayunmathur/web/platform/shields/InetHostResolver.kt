package com.vayunmathur.web.platform.shields

import com.vayunmathur.web.domain.HostResolver
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The production [HostResolver].
 *
 * `InetAddress.getAllByName` has no timeout, so the lookup runs on a pooled thread and is
 * abandoned after [TIMEOUT_MS]. Blocking the WebView background thread is legal; stalling it
 * for the several seconds a dead DNS server can take is not.
 */
object InetHostResolver : HostResolver {

    private const val TIMEOUT_MS = 1000L

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lan-dns").apply { isDaemon = true }
    }

    override fun resolve(host: String): List<String> {
        val lookup = executor.submit<List<String>> {
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
        }
        return try {
            lookup.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            lookup.cancel(true)
            emptyList()
        }
    }
}
