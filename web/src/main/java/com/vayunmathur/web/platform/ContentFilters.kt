package com.vayunmathur.web.platform

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.util.Locale

private const val TAG = "ContentFilters"

/**
 * The two supervision content-filter switches, and what this browser does about them.
 *
 * Settings writes `Settings.Secure.browser_content_filters_enabled` and
 * `search_content_filters_enabled` when a parent turns on web content filtering under Parental
 * controls. **Nothing in AOSP reads them** - a grep of `frameworks/base` and `packages` finds
 * only the declarations and the supervision code that writes them. They are advisory flags that
 * browsers and search apps are expected to honour voluntarily, which is why those switches did
 * nothing on this device until now.
 *
 * Read live rather than cached at startup: a parent toggling the switch expects the child's open
 * browser to comply, not to comply after the next cold start. Values are cheap to read and
 * [observe] exists for the UI to recompose on.
 */
object ContentFilters {

    /** "Try to block explicit sites". */
    private const val BROWSER_FILTER = "browser_content_filters_enabled"

    /** "Filter explicit results in search". */
    private const val SEARCH_FILTER = "search_content_filters_enabled"

    fun blockExplicitSites(context: Context): Boolean = isOn(context, BROWSER_FILTER)

    fun filterSearchResults(context: Context): Boolean = isOn(context, SEARCH_FILTER)

    /** Whether either switch is on, i.e. whether this device is under content supervision. */
    fun anyEnabled(context: Context): Boolean =
        blockExplicitSites(context) || filterSearchResults(context)

    private fun isOn(context: Context, key: String): Boolean =
        runCatching { Settings.Secure.getInt(context.contentResolver, key, 0) == 1 }
            .getOrElse {
                Log.w(TAG, "could not read $key", it)
                false
            }

    /**
     * Calls [onChanged] whenever either switch changes. Returns a handle to unregister.
     *
     * Both keys are watched with one observer because every caller cares about the pair.
     */
    fun observe(context: Context, onChanged: () -> Unit): ContentObserver {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = onChanged()
        }
        val resolver = context.contentResolver
        runCatching {
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(BROWSER_FILTER), false, observer,
            )
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(SEARCH_FILTER), false, observer,
            )
        }.onFailure { Log.w(TAG, "could not observe the content filter switches", it) }
        return observer
    }
}

/**
 * Host-suffix blocklist for the "block explicit sites" switch.
 *
 * ## What this is, stated plainly
 *
 * A blocklist, not a classifier. It blocks what it lists and nothing else, so it will always
 * miss sites, and its usefulness is entirely a function of how good the list is. It is not a
 * substitute for supervision and should not be described to a parent as one.
 *
 * The list ships as a plain asset so it can be replaced wholesale without touching code - see
 * `web/src/main/assets/contentfilter/adult-domains.txt`. Matching is on the registrable host
 * suffix, so one entry covers every subdomain.
 *
 * Deliberately NOT routed through the Brave shields engine, even though that already parses
 * filter lists: the shields engine is a single multi-megabyte snapshot built once and cached,
 * and rebuilding it every time a parent flips a switch would stall the browser for seconds. A
 * flat host set costs nothing when the switch is off and a hash lookup when it is on.
 */
object AdultSites {

    private const val ASSET = "contentfilter/adult-domains.txt"

    @Volatile private var domains: Set<String>? = null

    /** Whether [host] is on the list, matching any subdomain of a listed domain. */
    fun blocks(context: Context, host: String): Boolean {
        if (host.isEmpty()) return false
        val set = domains ?: load(context).also { domains = it }
        if (set.isEmpty()) return false
        val lower = host.lowercase(Locale.ROOT).removePrefix("www.")
        if (lower in set) return true
        // Walk the suffixes: a.b.example.com matches an entry for example.com.
        var index = lower.indexOf('.')
        while (index in 0..<lower.length - 1) {
            if (lower.substring(index + 1) in set) return true
            index = lower.indexOf('.', index + 1)
        }
        return false
    }

    private fun load(context: Context): Set<String> = runCatching {
        context.applicationContext.assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.lowercase(Locale.ROOT) }
                .toSet()
        }
    }.getOrElse {
        Log.w(TAG, "no adult-domain list; the site filter will block nothing", it)
        emptySet()
    }
}
