package com.vayunmathur.web.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.web.R
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconArrowForward
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShield
import com.vayunmathur.web.Route
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.platform.shields.ShieldsWebViewClient
import com.vayunmathur.web.platform.BrowserUtils
import com.vayunmathur.web.platform.PwaHelper
import com.vayunmathur.web.platform.PwaInfo
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.isNewTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserPage(
    viewModel: WebViewModel,
    backStack: NavBackStack<Route>,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val webViewPool = remember { mutableStateMapOf<String, WebView>() }

    LaunchedEffect(viewModel.tabs.size) {
        if (viewModel.tabs.isEmpty()) viewModel.newTab()
    }

    val activeTab = viewModel.activeTab
    val canGoBack = activeTab?.let { viewModel.getCanGoBack(it.id) } ?: false
    val canGoForward = activeTab?.let { viewModel.getCanGoForward(it.id) } ?: false
    val progress = activeTab?.let { viewModel.getProgress(it.id) } ?: 0f
    val isNewTabActive = activeTab?.isNewTab ?: true

    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val isCurrentBookmarked = activeTab?.url?.let { url -> url.isNotBlank() && bookmarks.any { it.url == url } } ?: false

    val shieldHost = activeTab?.url?.takeIf { it.startsWith("http") }?.let { BrowserUtils.hostFromUrl(it) }
    // Navigating away from the site the panel describes has to close it, otherwise it would
    // silently start editing a different host's settings.
    LaunchedEffect(shieldHost) { viewModel.showShieldsPanel = false }

    val multiDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.deliverFileChooserResult(uris.toTypedArray().takeIf { it.isNotEmpty() })
    }
    val singleDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.deliverFileChooserResult(uri?.let { arrayOf(it) })
    }

    var showMenu by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var linkContextMenuUrl by remember { mutableStateOf<String?>(null) }
    val searchFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = viewModel.showTabSwitcher) { viewModel.showTabSwitcher = false }
    BackHandler(enabled = !viewModel.showTabSwitcher && viewModel.omniboxFocused) {
        viewModel.omniboxFocused = false
        focusManager.clearFocus()
    }
    BackHandler(enabled = !viewModel.showTabSwitcher && !viewModel.omniboxFocused && canGoBack) {
        activeTab?.let { tab -> webViewPool[tab.id]?.goBack() }
    }

    LaunchedEffect(viewModel.omniboxFocused) {
        if (viewModel.omniboxFocused) {
            // Let the new Scaffold compose before requesting focus
            kotlinx.coroutines.delay(100)
            try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
            keyboardController?.show()
        }
    }

    val currentDraft = viewModel.searchDraft
    val filteredBookmarks = remember(currentDraft, bookmarks) {
        if (currentDraft.isBlank()) bookmarks.take(5)
        else bookmarks.filter { it.url.contains(currentDraft, true) || it.title.contains(currentDraft, true) }.take(8)
    }
    val filteredHistory = remember(currentDraft, history) {
        if (currentDraft.isBlank()) history.take(10)
        else history.filter { it.url.contains(currentDraft, true) || it.title.contains(currentDraft, true) }.take(15)
    }

    Box(Modifier.fillMaxSize()) {
        if (viewModel.omniboxFocused) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = {
                                focusManager.clearFocus()
                                viewModel.omniboxFocused = false
                            }) { IconBack() }
                        },
                        title = {
                            OutlinedTextField(
                                value = viewModel.searchDraft,
                                onValueChange = { viewModel.searchDraft = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester),
                                placeholder = { Text(stringResource(R.string.search_or_enter_address)) },
                                leadingIcon = { IconSearch() },
                                trailingIcon = if (viewModel.searchDraft.isNotEmpty()) {
                                    {
                                        IconButton(onClick = { viewModel.searchDraft = "" }) { IconClose() }
                                    }
                                } else null,
                                shape = RoundedCornerShape(28.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    if (viewModel.searchDraft.isNotBlank()) {
                                        viewModel.navigateActiveTab(viewModel.searchDraft)
                                        focusManager.clearFocus()
                                        viewModel.omniboxFocused = false
                                    }
                                })
                            )
                        }
                    )
                }
            ) { paddingValues ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                ) {
                    if (currentDraft.isNotBlank()) {
                        item {
                            ListItem(
                                headlineContent = { Text(currentDraft, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text(
                                        text = BrowserUtils.hostFromUrl(
                                            BrowserUtils.toNavigationUrl(currentDraft, viewModel.searchEngine)
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingContent = { IconSearch() },
                                modifier = Modifier.clickable {
                                    viewModel.navigateActiveTab(currentDraft)
                                    focusManager.clearFocus()
                                    viewModel.omniboxFocused = false
                                }
                            )
                        }
                        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                    }

                    if (filteredBookmarks.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.bookmarks),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                        items(filteredBookmarks, key = { "bm-${it.id}" }) { bm ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        bm.title.ifBlank { bm.url },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        BrowserUtils.prettyUrl(bm.url),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                leadingContent = { IconSearch() },
                                modifier = Modifier.clickable {
                                    viewModel.navigateActiveTab(bm.url)
                                    focusManager.clearFocus()
                                    viewModel.omniboxFocused = false
                                }
                            )
                        }
                    }

                    if (filteredHistory.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.history),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).padding(top = if (filteredBookmarks.isNotEmpty()) 12.dp else 0.dp)
                            )
                        }
                        items(filteredHistory, key = { "h-${it.id}" }) { h ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        h.title.ifBlank { h.url },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        BrowserUtils.prettyUrl(h.url),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                leadingContent = { IconSearch() },
                                modifier = Modifier.clickable {
                                    viewModel.navigateActiveTab(h.url)
                                    focusManager.clearFocus()
                                    viewModel.omniboxFocused = false
                                }
                            )
                        }
                    }
                }
            }
        } else {
            BrowserChrome(
                omniboxText = viewModel.omniboxText,
                tabCount = viewModel.tabs.size,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                progress = if (activeTab != null && !isNewTabActive) progress else 0f,
                onBack = { if (canGoBack) activeTab?.let { webViewPool[it.id]?.goBack() } },
                onForward = { if (canGoForward) activeTab?.let { webViewPool[it.id]?.goForward() } },
                onOmniboxClick = {
                    val full = activeTab?.url?.let { if (it.isBlank() || it == "about:blank") "" else it } ?: ""
                    viewModel.searchDraft = full
                    viewModel.omniboxFocused = true
                },
                onTabSwitcherClick = { viewModel.showTabSwitcher = true },
                shieldHost = shieldHost,
                blockedCount = activeTab?.let { viewModel.blockedCount(it.id) } ?: 0,
                onShieldClick = { viewModel.showShieldsPanel = true },
                onMenuClick = { showMenu = true },
                menu = {
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (!isNewTabActive) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reload)) },
                                onClick = {
                                    showMenu = false
                                    activeTab?.let { webViewPool[it.id]?.reload() }
                                }
                            )
                        }

                        if (activeTab != null && !isNewTabActive) {
                            val pwa = viewModel.getPwaInfo(activeTab.id)
                            val pinSupported = PwaHelper.isPinSupported(context)
                            val label = if (pwa?.hasManifest == true) "Install app" else "Add to Home screen"
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    showMenu = false
                                    showInstallDialog = true
                                },
                                enabled = activeTab.url.isNotBlank() && activeTab.url.startsWith("http") && pinSupported
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (isCurrentBookmarked) "Remove bookmark" else "Add bookmark") },
                            onClick = {
                                showMenu = false
                                activeTab?.let { tab ->
                                    if (tab.url.isBlank()) return@let
                                    if (isCurrentBookmarked) {
                                        bookmarks.find { it.url == tab.url }?.let { viewModel.removeBookmark(it) }
                                    } else viewModel.addBookmark(tab.url, tab.title.ifBlank { tab.url })
                                }
                            }
                        )
                        DropdownMenuItem(text = { Text(stringResource(UiR.string.share)) }, onClick = {
                            showMenu = false
                            activeTab?.let { tab ->
                                if (tab.url.isBlank()) return@let
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, tab.url)
                                    type = "text/plain"
                                }
                                ExternalIntents.launch(context, android.content.Intent.createChooser(sendIntent, context.getString(R.string.share_link)))
                            }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.new_tab)) }, onClick = { showMenu = false; viewModel.newTab() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.new_private_tab)) }, onClick = { showMenu = false; viewModel.newTab(isPrivate = true) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.history)) }, onClick = { showMenu = false; backStack.add(Route.History) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks)) }, onClick = { showMenu = false; backStack.add(Route.Bookmarks) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.downloads)) }, onClick = { showMenu = false; backStack.add(Route.Downloads) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.installed_apps)) }, onClick = { showMenu = false; backStack.add(Route.InstalledSites) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.site_data)) }, onClick = { showMenu = false; backStack.add(Route.SiteData) })
                        DropdownMenuItem(text = { Text(stringResource(UiR.string.settings)) }, onClick = { showMenu = false; backStack.add(Route.Settings) })
                    }
                },
            ) { paddingValues ->
                Column(Modifier.fillMaxSize().padding(paddingValues)) {
                    if (isNewTabActive) {
                        QuickAccess(
                            bookmarks = bookmarks.take(12),
                            history = history.take(8),
                            onOpenUrl = { url -> activeTab?.let { viewModel.onTabUrlChange(it.id, url) } },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (activeTab != null) {
                        Box(Modifier.fillMaxSize()) {
                            // Force a new WebViewBrowser composition per tabId so the AndroidView
                            // factory runs and loads the new URL immediately. Without this, the
                            // same AndroidView instance is reused across tab switches and the old
                            // page remains visible until an update triggers, causing topbar/content
                            // mismatch when an external intent opens a new tab.
                            key(activeTab.id) {
                                WebViewBrowser(
                                    tabId = activeTab.id,
                                    initialUrl = activeTab.url,
                                    viewModel = viewModel,
                                    webViewPool = webViewPool,
                                    onRequestNewTab = { url -> viewModel.newTab(url = url) },
                                    onLinkLongPress = { url -> linkContextMenuUrl = url },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }

        if (viewModel.showTabSwitcher) {
            TabSwitcher(
                tabs = viewModel.tabs,
                activeTabId = viewModel.activeTabId,
                onSwitch = { viewModel.switchToTab(it) },
                onClose = { viewModel.closeTab(it) },
                onNewTab = { viewModel.newTab(isPrivate = viewModel.incognito || viewModel.activeTab?.isPrivate == true) },
                onNewIncognitoTab = { viewModel.newTab(isPrivate = true) },
                onNewWindow = {
                    viewModel.showTabSwitcher = false
                    com.vayunmathur.web.launchNewWebWindow(context, incognito = false)
                },
                onNewIncognitoWindow = {
                    viewModel.showTabSwitcher = false
                    com.vayunmathur.web.launchNewWebWindow(context, incognito = true)
                },
                isIncognitoWindow = viewModel.incognito || viewModel.activeTab?.isPrivate == true,
                onDismiss = { viewModel.showTabSwitcher = false },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (viewModel.showShieldsPanel && shieldHost != null) {
            ShieldsPanel(
                host = shieldHost,
                blockedCount = activeTab?.let { viewModel.blockedCount(it.id) } ?: 0,
                viewModel = viewModel,
                onReload = {
                    activeTab?.let { tab ->
                        webViewPool[tab.id]?.let { webView ->
                            // Re-register before reloading, not after: document-start scripts
                            // only apply to documents that start loading after the call.
                            (webView.webViewClient as? ShieldsWebViewClient)
                                ?.installFarbling(webView, viewModel.farblingConfig())
                            webView.reload()
                        }
                    }
                },
                onDismiss = { viewModel.showShieldsPanel = false },
            )
        }

        viewModel.pendingPermissionPrompt?.let { prompt ->
            PermissionPromptSheet(
                origin = prompt.origin,
                types = prompt.types,
                onGrant = { granted ->
                    prompt.onGrant(granted)
                    viewModel.clearPermissionPrompt()
                },
                onDeny = {
                    prompt.onDeny()
                    viewModel.clearPermissionPrompt()
                }
            )
        }

        viewModel.pendingGeolocationPrompt?.let { (origin, _, _) ->
            GeolocationPromptSheet(
                origin = origin,
                onAllow = { viewModel.grantGeolocation(origin) },
                onDeny = { viewModel.denyGeolocation() }
            )
        }

        viewModel.pendingFileChooser?.let { (_, params) ->
            val mimeTypes = try { params.acceptTypes.toList() } catch (_: Exception) { emptyList() }
            val allowMultiple = try { params.mode == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE } catch (_: Exception) { false }
            FileChooserSheet(
                mimeTypes = mimeTypes,
                onFiles = { uris ->
                    if (uris == null) viewModel.clearFileChooser() else viewModel.deliverFileChooserResult(uris)
                },
                onCancel = { viewModel.clearFileChooser() },
                onTriggerPicker = {
                    try {
                        if (allowMultiple) {
                            multiDocLauncher.launch(mimeTypes.filter { it.isNotBlank() }.toTypedArray().takeIf { it.isNotEmpty() } ?: arrayOf("*/*"))
                        } else {
                            val mt = mimeTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
                            singleDocLauncher.launch(arrayOf(mt))
                        }
                    } catch (_: Exception) { viewModel.clearFileChooser() }
                }
            )
        }

        linkContextMenuUrl?.let { linkUrl ->
            LinkContextMenu(
                url = linkUrl,
                onDismiss = { linkContextMenuUrl = null },
                onCopyLink = {
                    com.vayunmathur.library.ui.ExternalIntents.copyToClipboard(
                        context,
                        linkUrl,
                        linkUrl,
                    )
                    com.vayunmathur.library.util.AppMessages.show(context.getString(R.string.link_copied))
                },
                onShareLink = {
                    com.vayunmathur.library.ui.ExternalIntents.shareText(
                        context,
                        linkUrl,
                        context.getString(R.string.share_link),
                    )
                },
                onOpenInNewTab = { viewModel.newTab(url = linkUrl) },
            )
        }

        if (showInstallDialog) {
            val tabId = activeTab?.id
            val url = activeTab?.url ?: ""
            val pwa = tabId?.let { viewModel.getPwaInfo(it) }
            val fallbackTitle = tabId?.let { viewModel.getTabTitle(it).ifBlank { activeTab?.title ?: "" } } ?: ""
            val defaultTitle = PwaHelper.displayTitle(pwa, fallbackTitle, url)
            var draftTitle by remember(url, defaultTitle) { mutableStateOf(defaultTitle) }

            AlertDialog(
                onDismissRequest = { showInstallDialog = false },
                title = { Text(if (pwa?.hasManifest == true) "Install app?" else "Add to Home screen?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (pwa?.hasManifest == true)
                                "This site has a web manifest — install as standalone app."
                            else
                                "Create pinned shortcut that opens in standalone mode (PwaActivity). Works for any site via best icon (apple-touch-icon, 192x192).",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(BrowserUtils.prettyUrl(url), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (pwa?.iconUrl != null) {
                                    Text(stringResource(R.string.icon, pwa.iconUrl.take(64)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (pwa?.themeColor != null) {
                                    Text(stringResource(R.string.theme, pwa.themeColor), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = draftTitle,
                            onValueChange = { draftTitle = it },
                            label = { Text(stringResource(R.string.app_name_2)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val finalTitle = draftTitle.ifBlank { defaultTitle }
                            showInstallDialog = false
                            if (tabId != null && url.startsWith("http")) {
                                viewModel.installAsPwa(
                                    tabId = tabId,
                                    url = url,
                                    pwaInfo = pwa?.copy(name = finalTitle) ?: PwaInfo(
                                        name = finalTitle,
                                        origin = BrowserUtils.originFromUrl(url),
                                        startUrl = url
                                    )
                                )
                            }
                        },
                        enabled = draftTitle.isNotBlank() || defaultTitle.isNotBlank()
                    ) { Text(stringResource(UiR.string.add)) }
                },
                dismissButton = {
                    TextButton(onClick = { showInstallDialog = false }) { Text(stringResource(UiR.string.cancel)) }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserChrome(
    omniboxText: String,
    tabCount: Int,
    canGoBack: Boolean = false,
    canGoForward: Boolean = false,
    progress: Float = 0f,
    onBack: () -> Unit = {},
    onForward: () -> Unit = {},
    onOmniboxClick: () -> Unit = {},
    onTabSwitcherClick: () -> Unit = {},
    shieldHost: String? = null,
    blockedCount: Int = 0,
    onShieldClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    menu: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack, enabled = canGoBack) { IconBack() }
                            IconButton(onClick = onForward, enabled = canGoForward) { IconArrowForward() }
                        }
                    },
                    title = {
                        DisplayOnlyAddressPill(
                            fullUrl = omniboxText,
                            onClick = onOmniboxClick,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    actions = {
                        if (shieldHost != null) {
                            ShieldChip(blockedCount = blockedCount, onClick = onShieldClick)
                            Spacer(Modifier.width(4.dp))
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(onClick = onTabSwitcherClick)
                        ) {
                            Text(
                                text = tabCount.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = onMenuClick) { IconMoreVert() }
                        menu()
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
                if (progress in 0.01f..0.99f) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
        },
        content = content,
    )
}

/**
 * Toolbar shield. Shows the number of requests blocked on the current page, which is the
 * only feedback the user gets that shields are doing anything.
 */
@Composable
private fun ShieldChip(blockedCount: Int, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconShield(Modifier.size(18.dp))
            if (blockedCount > 0) {
                Spacer(Modifier.width(4.dp))
                Text(blockedCount.toString(), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun DisplayOnlyAddressPill(
    fullUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Now matches CommonSearchBar visually: OutlinedTextField 28dp rounded, search icon, same padding.
    Box(modifier = modifier) {
        OutlinedTextField(
            value = fullUrl,
            onValueChange = {},
            readOnly = true,
            placeholder = { Text(stringResource(R.string.search_or_enter_address)) },
            leadingIcon = { IconSearch() },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth()
        )
        // Overlay to handle tap without focusing the field
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(28.dp))
                .clickable(onClick = onClick)
        )
    }
}

@Composable
private fun QuickAccess(
    bookmarks: List<com.vayunmathur.web.data.Bookmark>,
    history: List<com.vayunmathur.web.data.HistoryEntry>,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (bookmarks.isNotEmpty()) {
            item { Text(stringResource(R.string.bookmarks), style = MaterialTheme.typography.titleMedium) }
            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 16.dp)) {
                    items(bookmarks, key = { it.id }) { bm ->
                        Card(
                            modifier = Modifier.width(140.dp).clickable { onOpenUrl(bm.url) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                                    Text(bm.title.take(1).uppercase().ifBlank { "B" }, style = MaterialTheme.typography.titleSmall)
                                }
                                Text(bm.title.ifBlank { BrowserUtils.hostFromUrl(bm.url) }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            item { Text(stringResource(R.string.recent), style = MaterialTheme.typography.titleMedium) }
            items(history, key = { it.id }) { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenUrl(entry.url) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(36.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(entry.title.take(1).uppercase().ifBlank { "H" }, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title.ifBlank { entry.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(BrowserUtils.prettyUrl(entry.url), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.blank_new_tab_tap_the_address_pill_to_se),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

/** Already stateless — public only so the store-listing previews can render it. */
@Composable
private fun TabSwitcher(
    tabs: List<com.vayunmathur.web.util.BrowserTab>,
    activeTabId: String?,
    onSwitch: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onNewIncognitoTab: () -> Unit = {},
    onNewWindow: () -> Unit = onNewTab,
    onNewIncognitoWindow: () -> Unit = onNewIncognitoTab,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isIncognitoWindow: Boolean = tabs.find { it.id == activeTabId }?.isPrivate == true,
) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.98f))) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(pluralStringResource(R.plurals.tabs, tabs.size, tabs.size)) },
                navigationIcon = { com.vayunmathur.library.ui.IconButton(onClick = onDismiss) { IconClose() } },
                actions = { com.vayunmathur.library.ui.IconButton(onClick = onNewTab) { IconAdd() } }
            )
            LazyColumn(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tabs, key = { it.id }) { tab ->
                    val isActive = tab.id == activeTabId
                    val displayTitle = when {
                        tab.isNewTab -> "New Tab"
                        tab.title.isNotBlank() -> tab.title
                        else -> BrowserUtils.prettyUrl(tab.url).ifBlank { "New Tab" }
                    }
                    val displayUrl = if (tab.isNewTab) "" else tab.url
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onSwitch(tab.id) },
                        colors = CardDefaults.cardColors(containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (tab.isPrivate) Text(stringResource(R.string.private_prefix), style = MaterialTheme.typography.labelSmall)
                                    Text(displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                }
                                if (displayUrl.isNotBlank()) {
                                    Text(displayUrl, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            com.vayunmathur.library.ui.IconButton(onClick = { onClose(tab.id) }) { IconClose() }
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (isIncognitoWindow) onNewIncognitoTab() else onNewTab()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(
                                    if (isIncognitoWindow) R.string.new_incognito_tab else R.string.new_tab
                                )
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onNewWindow, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.new_window)) }
                            OutlinedButton(onClick = onNewIncognitoWindow, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.new_incognito_window)) }
                        }
                    }
                }
            }
        }
    }
}
