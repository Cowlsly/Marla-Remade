package com.vayunmathur.web.domain.shields

/**
 * Brave's query-parameter stripping and HTTPS upgrading for top-level navigations.
 *
 * The engine's own `$removeparam` rules cover most tracking parameters, but they only
 * apply to requests it inspects. Brave additionally strips a fixed list on every
 * navigation, which is what this reproduces — it catches links pasted into the omnibox
 * and cross-site redirects that never reach a subresource check.
 *
 * Pure Kotlin (no `android.net.Uri`) so it runs in JVM unit tests.
 */
object UrlCleaner {

    /**
     * Exact parameter names Brave removes. Kept in sync with `brave-core`'s
     * `query_filter.cc` tracking-parameter list.
     */
    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "msclkid", "mc_eid", "mkt_tok",
        "igshid", "igsh", "twclid", "yclid", "ttclid", "rb_clickid", "s_cid",
        "wickedid", "oly_anon_id", "oly_enc_id", "vero_conv", "vero_id",
        "_hsenc", "_hsmi", "__hssc", "__hstc", "hsCtaTracking", "__s",
        "_openstat", "wt_zmc", "guccounter", "guce_referrer", "guce_referrer_sig",
        "cmpid", "os_ehash", "pk_campaign", "pk_kwd", "piwik_campaign", "piwik_kwd",
        "spm", "scm", "share_source", "share_medium", "share_plat", "share_tag",
        "share_session_id", "share_from", "unique_k", "vd_source",
    )

    /** Prefixes covering whole families: `utm_*`, `ga_*`, `hsa_*`, `matomo_*`. */
    private val TRACKING_PREFIXES = listOf("utm_", "ga_", "hsa_", "matomo_")

    private fun isTracking(name: String): Boolean {
        val lower = name.lowercase()
        return lower in TRACKING_PARAMS || TRACKING_PREFIXES.any { lower.startsWith(it) }
    }

    /**
     * Removes tracking parameters from [url], preserving parameter order, repeated keys
     * and the fragment. Returns [url] unchanged when there is nothing to strip, so callers
     * can compare by identity to decide whether a reload is warranted.
     */
    fun clean(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url

        val fragmentStart = url.indexOf('#', queryStart)
        val query = if (fragmentStart < 0) {
            url.substring(queryStart + 1)
        } else {
            url.substring(queryStart + 1, fragmentStart)
        }
        if (query.isEmpty()) return url

        val kept = query.split('&').filter { param ->
            param.isNotEmpty() && !isTracking(param.substringBefore('='))
        }
        if (kept.size == query.split('&').count { it.isNotEmpty() }) return url

        val prefix = url.substring(0, queryStart)
        val fragment = if (fragmentStart < 0) "" else url.substring(fragmentStart)
        return if (kept.isEmpty()) prefix + fragment else "$prefix?${kept.joinToString("&")}$fragment"
    }

    /**
     * The `https://` form of an `http://` [url], or null when it is already secure or is
     * an address HTTPS cannot serve.
     *
     * localhost and bare IPs are left alone: they have no publicly trusted certificate and
     * upgrading them breaks local development servers, which is also Brave's behaviour.
     */
    fun httpsUpgrade(url: String): String? {
        if (!url.startsWith("http://", ignoreCase = true)) return null
        val host = hostOf(url)
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost")) return null
        if (isIpLiteral(host)) return null
        return "https://" + url.substring("http://".length)
    }

    private fun hostOf(url: String): String {
        val afterScheme = url.substringAfter("://", "")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        return authority.substringAfter('@').substringBefore(':').lowercase()
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.startsWith("[")) return true // IPv6
        val octets = host.split('.')
        return octets.size == 4 && octets.all { part ->
            part.isNotEmpty() && part.all { it.isDigit() } && part.toInt() <= 255
        }
    }
}
