package com.vayunmathur.passwords.domain

/**
 * Host comparison for deciding whether a stored site and a relying party (or a page being
 * autofilled) are the same site.
 *
 * Deliberately free of `android.net.Uri` so it can be unit tested on the JVM.
 */
object DomainMatch {

    /** Reduces a stored website or rpId to a bare lowercase host. */
    fun normalizeSite(raw: String): String {
        var s = raw.trim().lowercase()
        s = s.substringAfter("://")
        s = s.substringBefore('/')
        s = s.substringBefore('?')
        // Strip credentials and port: "user:pw@host:8443" -> "host".
        s = s.substringAfterLast('@')
        s = s.substringBefore(':')
        s = s.removeSuffix(".")
        return s.removePrefix("www.")
    }

    /** Android package targets are stored alongside websites but are not hosts. */
    fun isAndroidPackageSite(raw: String): Boolean {
        val s = raw.trim().lowercase()
        if (s.startsWith("android-app://")) return true
        // A bare package name: dotted, no scheme, no path, and no TLD-looking last label.
        return !s.contains("://") && !s.contains('/') && s.count { it == '.' } >= 2 &&
            s.startsWith("com.")
    }

    /**
     * True when [host] is [parent] or sits underneath it. Dot-aware on purpose: a plain
     * `endsWith` would make `notexample.com` a match for `example.com`.
     */
    fun isSameSiteOrSubdomain(host: String, parent: String): Boolean {
        if (host.isEmpty() || parent.isEmpty()) return false
        return host == parent || host.endsWith(".$parent")
    }
}
