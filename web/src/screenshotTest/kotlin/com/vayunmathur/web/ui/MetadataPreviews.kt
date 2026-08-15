package com.vayunmathur.web.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.web.data.Bookmark
import com.vayunmathur.web.data.BookmarkFolder
import com.vayunmathur.web.data.HistoryEntry
import com.vayunmathur.web.platform.BrowserTab

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Fixed so the images are byte-identical from a clean checkout; neither screen shows a date. */
private const val JUL_2026 = 1_753_900_000_000L

/**
 * Store listing images for `:web`. See `common-conventions-preview-metadata`.
 *
 * A browser is mostly a WebView, which cannot render here, so these show the chrome around
 * it: the new-tab landing page, the tab switcher and bookmarks. [BrowserChrome] was split out
 * of [BrowserPage] and [BookmarksScreen] out of [BookmarksPage] to make that possible;
 * [TabSwitcher] and [QuickAccess] were already stateless.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 */
class MetadataPreviews {

    private val bookmarks = listOf(
        Bookmark(id = 1, url = "https://f-droid.org/packages/", title = "F-Droid", createdAt = JUL_2026),
        Bookmark(id = 2, url = "https://en.wikipedia.org/wiki/Main_Page", title = "Wikipedia", createdAt = JUL_2026),
        Bookmark(id = 3, url = "https://news.ycombinator.com", title = "Hacker News", createdAt = JUL_2026),
        Bookmark(id = 4, url = "https://developer.android.com/jetpack/compose", title = "Jetpack Compose", createdAt = JUL_2026),
        Bookmark(id = 5, url = "https://www.openstreetmap.org", title = "OpenStreetMap", createdAt = JUL_2026),
        // Filed under "Reading" — the unfiled list the screen opens on deliberately omits these.
        Bookmark(id = 6, url = "https://kotlinlang.org/docs/coroutines-guide.html", title = "Coroutines guide", createdAt = JUL_2026, folderId = 1),
        Bookmark(id = 7, url = "https://source.android.com/docs/security", title = "Android security", createdAt = JUL_2026, folderId = 1),
    )

    private val folders = listOf(
        BookmarkFolder(id = 1, name = "Reading", createdAt = JUL_2026),
        BookmarkFolder(id = 2, name = "Work", createdAt = JUL_2026),
    )

    private val history = listOf(
        HistoryEntry(id = 1, url = "https://duckduckgo.com/?q=wireguard+mtu", title = "wireguard mtu at DuckDuckGo", visitedAt = JUL_2026),
        HistoryEntry(id = 2, url = "https://en.wikipedia.org/wiki/Curve25519", title = "Curve25519 — Wikipedia", visitedAt = JUL_2026),
        HistoryEntry(id = 3, url = "https://f-droid.org/packages/com.vayunmathur.web/", title = "Web | F-Droid", visitedAt = JUL_2026),
        HistoryEntry(id = 4, url = "https://developer.android.com/reference/android/webkit/WebView", title = "WebView — Android Developers", visitedAt = JUL_2026),
        HistoryEntry(id = 5, url = "https://www.openstreetmap.org/#map=12/47.61/-122.33", title = "OpenStreetMap", visitedAt = JUL_2026),
    )

    private val tabs = listOf(
        BrowserTab(id = "t1", url = "", title = ""),
        BrowserTab(id = "t2", url = "https://en.wikipedia.org/wiki/Curve25519", title = "Curve25519 — Wikipedia"),
        BrowserTab(id = "t3", url = "https://news.ycombinator.com", title = "Hacker News"),
        BrowserTab(id = "t4", url = "https://duckduckgo.com/?q=web+manifest", title = "web manifest at DuckDuckGo", isPrivate = true),
    )

    @PreviewTest
    @Preview(name = "1-new-tab", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1NewTab() {
        DynamicTheme(darkTheme = true) {
            // Blank omnibox: this is what a fresh tab looks like before anything is typed.
            BrowserChrome(omniboxText = "", tabCount = tabs.size) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    QuickAccess(
                        bookmarks = bookmarks,
                        history = history,
                        onOpenUrl = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    @PreviewTest
    @Preview(name = "2-tabs", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Tabs() {
        DynamicTheme(darkTheme = true) {
            TabSwitcher(
                tabs = tabs,
                activeTabId = "t2",
                onSwitch = {},
                onClose = {},
                onNewTab = {},
                onNewWindow = {},
                onNewIncognitoTab = {},
                onNewIncognitoWindow = {},
                onDismiss = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-bookmarks", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Bookmarks() {
        DynamicTheme(darkTheme = true) {
            BookmarksScreen(bookmarks = bookmarks, folders = folders)
        }
    }
}
