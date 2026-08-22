package com.vayunmathur.web.platform

import android.net.Uri
import com.vayunmathur.web.domain.LocalNetwork
import kotlinx.serialization.Serializable

@Serializable
data class BrowserTab(
    val id: String,
    val url: String = "",
    val title: String = "",
    val faviconUrl: String? = null,
    val isPrivate: Boolean = false,
)

val BrowserTab.isNewTab: Boolean
    get() = url.isBlank() || url == "about:blank"

enum class SearchEngine(
    val displayName: String,
    val searchUrl: String,
    val homepage: String,
) {
    GOOGLE("Google", "https://www.google.com/search?q=%s", "https://www.google.com"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com"),
    BING("Bing", "https://www.bing.com/search?q=%s", "https://www.bing.com"),
    BRAVE("Brave", "https://search.brave.com/search?q=%s", "https://search.brave.com"),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=%s", "https://www.startpage.com"),
    ECOSIA("Ecosia", "https://www.ecosia.org/search?q=%s", "https://www.ecosia.org"),
    QWANT("Qwant", "https://www.qwant.com/?q=%s", "https://www.qwant.com");

    fun buildQueryUrl(query: String): String =
        searchUrl.replace("%s", Uri.encode(query))

    companion object {
        val DEFAULT = DUCKDUCKGO
        fun fromName(name: String): SearchEngine =
            entries.find { it.name == name } ?: DEFAULT
    }
}

enum class CacheMode(val title: String, val description: String, val webSettingsValue: Int) {
    DEFAULT("Default", "Use HTTP cache as needed", android.webkit.WebSettings.LOAD_DEFAULT),
    CACHE_ELSE_NETWORK("Cache first", "Prefer cache, fastest", android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK),
    NO_CACHE("No cache", "Always from network", android.webkit.WebSettings.LOAD_NO_CACHE),
    CACHE_ONLY("Offline", "Cache only, offline mode", android.webkit.WebSettings.LOAD_CACHE_ONLY);
}

enum class SitePermissionType(val key: String, val displayName: String) {
    CAMERA("camera", "Camera"),
    MICROPHONE("mic", "Microphone"),
    LOCATION("location", "Location"),
    NOTIFICATIONS("notifications", "Notifications")
}

object BrowserUtils {
    // Deliberately requires a dot (or localhost, or a dotted quad): loosening this to accept
    // dotless hosts so `router` navigates would turn every one-word search - `weather`,
    // `kotlin` - into a failed navigation. `nas.local` and `192.168.1.1:8080` already match,
    // and `http://router` works through the explicit-scheme path below.
    private val URL_LIKE = Regex(
        "^(https?://)?([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}|localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$"
    )

    /** Kept for migration only â€” new tabs use "" and search uses selected engine. */
    const val HOMEPAGE = "https://duckduckgo.com"

    /** Full address for display â€” no truncation. Keep prettyUrl() for subtitles. */
    fun displayFullUrl(url: String): String = url

    fun looksLikeUrl(text: String): Boolean {
        if (text.contains(" ")) return false
        val trimmed = text.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return true
        return URL_LIKE.matches(trimmed)
    }

    /** Search or navigate using the selected engine. */
    fun toNavigationUrl(input: String, searchEngine: SearchEngine): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return searchEngine.homepage
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (looksLikeUrl(trimmed)) {
            // LAN targets are nearly all plain http and have no publicly trusted certificate,
            // so defaulting them to https would fail before the page ever loaded.
            val lan = LocalNetwork.isLanHostSyntactic(LocalNetwork.hostOf(trimmed))
            return if (lan) "http://$trimmed" else "https://$trimmed"
        }
        return searchEngine.buildQueryUrl(trimmed)
    }

    fun toNavigationUrl(input: String): String = toNavigationUrl(input, SearchEngine.DEFAULT)

    fun hostFromUrl(url: String): String {
        return try { Uri.parse(url).host ?: url } catch (_: Exception) { url }
    }

    fun originFromUrl(url: String): String {
        return try {
            val u = Uri.parse(url)
            val scheme = u.scheme ?: "https"
            val host = u.host ?: return url
            val port = if (u.port != -1) ":${u.port}" else ""
            "$scheme://$host$port"
        } catch (_: Exception) { url }
    }

    fun prettyUrl(url: String): String {
        if (url.isBlank()) return ""
        return try {
            val parsed = Uri.parse(url)
            val host = parsed.host ?: return url
            val path = parsed.path ?: ""
            val display = if (path.isEmpty() || path == "/") host else host + path
            if (display.length > 48) host else display
        } catch (_: Exception) { url }
    }
}
