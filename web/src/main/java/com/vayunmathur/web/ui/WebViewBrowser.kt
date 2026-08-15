package com.vayunmathur.web.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.vayunmathur.web.platform.shields.FarblingConfig
import com.vayunmathur.web.platform.shields.ShieldsWebViewClient
import com.vayunmathur.web.platform.BrowserUtils
import com.vayunmathur.web.platform.PwaHelper
import com.vayunmathur.web.platform.SitePermissionType
import com.vayunmathur.web.platform.WebViewModel

private const val TAG = "WebViewBrowser"

/**
 * Core WebView with:
 * - permission delegation: camera, mic, location, file chooser
 * - caching: HTTP cache (cacheMode), DOM storage (localStorage), database, offscreen preraster, geolocation DB
 * - cookies + localStorage/IndexedDB/SW tracked via JS probe + CookieManager, persisted in WebView profile dir + Room (StorageInfo)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewBrowser(
    tabId: String,
    initialUrl: String,
    viewModel: WebViewModel,
    modifier: Modifier = Modifier,
    onRequestNewTab: (String) -> Unit = {},
    webViewPool: MutableMap<String, WebView>,
    onLinkLongPress: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val holder = remember(tabId) { WebViewHolder() }

    // Observed so a shields change recomposes and re-registers the document-start script;
    // the view model's own mirror is a plain map and would not trigger anything.
    val siteShields by viewModel.shieldSettings.collectAsStateWithLifecycle()
    val farblingConfig = remember(siteShields, viewModel.shields) {
        FarblingConfig.of(viewModel.shields, siteShields.associate { it.host to it.toSettings() })
    }

    var pendingSysPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingSysPermissionRequest?.grant(pendingSysPermissionRequest?.resources ?: emptyArray())
        } else {
            pendingSysPermissionRequest?.deny()
        }
        pendingSysPermissionRequest = null
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingSysPermissionRequest?.grant(pendingSysPermissionRequest?.resources ?: emptyArray())
        } else {
            pendingSysPermissionRequest?.deny()
        }
        pendingSysPermissionRequest = null
    }

    val multiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            pendingSysPermissionRequest?.grant(pendingSysPermissionRequest?.resources ?: emptyArray())
        } else {
            pendingSysPermissionRequest?.deny()
        }
        pendingSysPermissionRequest = null
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            // grant via stored geolocation callback in ViewModel
            viewModel.pendingGeolocationPrompt?.let { (origin, _, _) ->
                viewModel.grantGeolocation(origin)
            }
        } else {
            viewModel.denyGeolocation()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            webViewPool[tabId]?.let { existing ->
                (existing.parent as? ViewGroup)?.removeView(existing)
                applySettings(existing, viewModel)
                existing.setOnLongClickListener {
                    val hit = existing.hitTestResult
                    val url: String? = when (hit?.type) {
                        WebView.HitTestResult.SRC_ANCHOR_TYPE,
                        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> hit.extra
                        else -> null
                    }
                    if (!url.isNullOrBlank()) {
                        onLinkLongPress(url)
                        return@setOnLongClickListener true
                    }
                    false
                }
                return@AndroidView existing
            }

            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, !viewModel.blockThirdPartyCookies)

                applySettings(this, viewModel)

                // Long-press on a link: intercept before WebView's default tooltip/context menu
                setOnLongClickListener {
                    val hit = hitTestResult
                    val hitType = hit?.type
                    val url: String? = when (hitType) {
                        WebView.HitTestResult.SRC_ANCHOR_TYPE,
                        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> hit.extra
                        else -> null
                    }
                    if (!url.isNullOrBlank()) {
                        onLinkLongPress(url)
                        return@setOnLongClickListener true
                    }
                    false
                }


                setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                    val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                    Log.d(TAG, "Download: $fileName $url")
                    viewModel.addDownload(url, fileName, mimeType, contentLength)
                    try {
                        val dm = ctx.getSystemService(android.app.DownloadManager::class.java)
                        val request = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                            setMimeType(mimeType)
                            addRequestHeader("User-Agent", userAgent)
                            setDescription("Downloading $fileName")
                            setTitle(fileName)
                            setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                        }
                        dm.enqueue(request)
                    } catch (e: Exception) {
                        Log.e(TAG, "Download enqueue failed", e)
                        try {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (_: Exception) {}
                    }
                })

                // The tab a WebView belongs to never changes, so capture privacy once instead
                // of reading the Compose tab list from the render thread.
                val isPrivateTab = viewModel.tabs.find { it.id == tabId }?.isPrivate == true

                webViewClient = object : ShieldsWebViewClient(
                    context = ctx,
                    shieldsFor = { host -> viewModel.shieldsFor(host, isPrivateTab) },
                    onBlocked = { _, _ -> viewModel.onRequestBlocked(tabId) },
                    onNavigate = { _, rewritten -> viewModel.onTabUrlChange(tabId, rewritten) },
                ) {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val scheme = request.url.scheme ?: return false
                        if (scheme !in setOf("http", "https", "about", "data", "blob", "javascript")) {
                            return com.vayunmathur.web.platform.openExternalUri(
                                ctx,
                                request.url.toString(),
                            ) { fallback -> view.loadUrl(fallback) }
                        }
                        return super.shouldOverrideUrlLoading(view, request)
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        viewModel.resetBlockedCount(tabId)
                        url?.let { viewModel.onTabUrlChange(tabId, it) }
                        viewModel.onTabCanGoBack(tabId, view.canGoBack())
                        viewModel.onTabCanGoForward(tabId, view.canGoForward())
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        url?.let { viewModel.onTabUrlChange(tabId, it) }
                        viewModel.onTabCanGoBack(tabId, view.canGoBack())
                        viewModel.onTabCanGoForward(tabId, view.canGoForward())
                        CookieManager.getInstance().flush()
                        val title = view.title?.takeIf { it.isNotBlank() } ?: url ?: ""
                        if (title.isNotBlank()) {
                            viewModel.onTabTitleChange(tabId, title)
                            viewModel.recordHistoryVisit(url ?: "", title)
                        }
                        url?.let { u ->
                            if (u.startsWith("http")) {
                                try {
                                    val origin = BrowserUtils.originFromUrl(u)
                                    val cookies = CookieManager.getInstance().getCookie(u)
                                    val cookieCount = cookies?.split(";")?.count { it.isNotBlank() } ?: 0
                                    view.evalJsForStorageInfo(origin, cookieCount, viewModel)
                                } catch (e: Exception) {
                                    Log.w(TAG, "storage snapshot failed", e)
                                }
                                // PWA / Add-to-Home detection: probe for manifest + best icon + theme-color
                                try {
                                    view.evaluateJavascript(PwaHelper.MANIFEST_PROBE_JS) { json ->
                                        val info = PwaHelper.parseProbeJson(json)
                                        if (info != null && info.origin.isNotBlank()) {
                                            viewModel.onPwaInfoDetected(tabId, info)
                                        }
                                        // no need to keep raw json
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "pwa probe failed", e)
                                }
                            }
                        }
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        url?.let { viewModel.onTabUrlChange(tabId, it) }
                        viewModel.onTabCanGoBack(tabId, view.canGoBack())
                        viewModel.onTabCanGoForward(tabId, view.canGoForward())
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        viewModel.onTabProgress(tabId, newProgress / 100f)
                    }

                    override fun onReceivedTitle(view: WebView, title: String?) {
                        if (!title.isNullOrBlank()) viewModel.onTabTitleChange(tabId, title)
                    }

                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String,
                        callback: GeolocationPermissions.Callback
                    ) {
                        // Private tabs: deny location without persisting
                        if (viewModel.tabs.find { it.id == tabId }?.isPrivate == true) {
                            callback.invoke(origin, false, false)
                            return
                        }

                        viewModel.requestGeolocation(
                            origin = origin,
                            onAllow = {
                                val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                if (!hasFine && !hasCoarse) {
                                    // Defer until system permission granted; keep geolocation callback pending via VM state
                                    // We invoke deny for now and re-prompt after system permission result via launcher which will call grantGeolocation again on next site request.
                                    // Better: hold callback in local and request system perms now, then invoke on result.
                                    locationPermissionLauncher.launch(
                                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                    )
                                    // We must retain the callback somewhere to invoke after permission result.
                                    // Store in tag of WebView temporarily — use a holder map keyed by origin.
                                    // Simplest: deny now and let site re-request which will then auto-grant because system perm will be granted and saved permission says allowed.
                                    callback.invoke(origin, false, false)
                                } else {
                                    callback.invoke(origin, true, false)
                                }
                            },
                            onDeny = { callback.invoke(origin, false, false) }
                        )
                    }

                    override fun onPermissionRequest(request: PermissionRequest) {
                        val origin = request.origin.toString()
                        if (viewModel.tabs.find { it.id == tabId }?.isPrivate == true) {
                            request.deny()
                            return
                        }

                        val resources = request.resources
                        val types = mutableListOf<SitePermissionType>()
                        if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) types.add(SitePermissionType.CAMERA)
                        if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) types.add(SitePermissionType.MICROPHONE)

                        if (types.isEmpty()) {
                            request.grant(resources)
                            return
                        }

                        viewModel.requestWebPermission(
                            origin = origin,
                            types = types,
                            grant = { grantedTypes ->
                                val needsCamera = SitePermissionType.CAMERA in grantedTypes
                                val needsMic = SitePermissionType.MICROPHONE in grantedTypes
                                val hasCamera = if (needsCamera) {
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                } else true
                                val hasMic = if (needsMic) {
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                } else true

                                if (!hasCamera || !hasMic) {
                                    pendingSysPermissionRequest = request
                                    when {
                                        needsCamera && needsMic -> multiPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                                        needsCamera -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        needsMic -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                    return@requestWebPermission
                                }

                                val toGrant = mutableListOf<String>()
                                if (SitePermissionType.CAMERA in grantedTypes && resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                                    toGrant.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                                }
                                if (SitePermissionType.MICROPHONE in grantedTypes && resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                                    toGrant.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                                }
                                if (toGrant.isNotEmpty()) request.grant(toGrant.toTypedArray()) else request.deny()
                            },
                            deny = { request.deny() }
                        )
                    }

                    override fun onShowFileChooser(
                        webView: WebView,
                        filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                        fileChooserParams: FileChooserParams
                    ): Boolean {
                        viewModel.requestFileChooser(filePathCallback, fileChooserParams)
                        return true
                    }

                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                        val newWebView = WebView(view.context).apply {
                            settings.javaScriptEnabled = viewModel.jsEnabled
                            settings.domStorageEnabled = true
                            settings.applySystemDarkMode()
                        }
                        newWebView.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                                onRequestNewTab(req.url.toString())
                                return true
                            }
                            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                                url?.let { onRequestNewTab(it) }
                                v.stopLoading()
                            }
                        }
                        transport.webView = newWebView
                        resultMsg.sendToTarget()
                        return true
                    }
                }

                val toLoad = if (initialUrl.isBlank()) "about:blank" else initialUrl
                // Before the first load: document-start scripts do not apply retroactively.
                (webViewClient as ShieldsWebViewClient).installFarbling(this, farblingConfig)
                loadUrl(toLoad)
            }.also {
                webViewPool[tabId] = it
                applySettings(it, viewModel)
            }
        },
        update = { webView ->
            val current = webView.url ?: ""
            val desired = viewModel.getCurrentUrl(tabId)
            if (desired.isNotBlank() && desired != current && !viewModel.omniboxFocused) {
                if (viewModel.activeTabId == tabId) {
                    // Always load when desired differs — the previous prog >= 1f guard prevented
                    // external intents from loading while the current page was still loading,
                    // causing topbar/content mismatch.
                    webView.loadUrl(desired)
                }
            }
            applySettings(webView, viewModel)
            (webView.webViewClient as? ShieldsWebViewClient)
                ?.installFarbling(webView, farblingConfig)
            // Keep the long-press handler in sync after pool reuse / recomposition
            webView.setOnLongClickListener {
                val hit = webView.hitTestResult
                val url: String? = when (hit?.type) {
                    WebView.HitTestResult.SRC_ANCHOR_TYPE,
                    WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> hit.extra
                    else -> null
                }
                if (!url.isNullOrBlank()) {
                    onLinkLongPress(url)
                    return@setOnLongClickListener true
                }
                false
            }
            holder.webView = webView
        }
    )

    DisposableEffect(tabId) { onDispose { } }
}

/**
 * Forwards the system light/dark setting to page content as `prefers-color-scheme`.
 *
 * WebView reads this from the hosting activity theme's `android:isLightTheme`, which
 * `Theme.Web` flips through its `values-night` variant. Enabling algorithmic darkening is what
 * opts the WebView into honouring that flag; it additionally auto-darkens pages that ship no
 * dark stylesheet of their own, and leaves pages that do handle dark mode to style themselves.
 */
internal fun WebSettings.applySystemDarkMode() {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, true)
    }
}

private fun applySettings(webView: WebView, viewModel: WebViewModel) {
    val settings = webView.settings

    settings.javaScriptEnabled = viewModel.jsEnabled
    settings.javaScriptCanOpenWindowsAutomatically = viewModel.jsEnabled

    // DOM storage = localStorage / sessionStorage, persisted in WebView data dir
    settings.domStorageEnabled = true
    settings.databaseEnabled = true

    // HTTP cache — user selectable for speed / offline
    settings.cacheMode = viewModel.cacheMode.webSettingsValue

    settings.allowFileAccess = true
    settings.allowContentAccess = true

    // Offscreen pre-raster speeds first paint
    settings.offscreenPreRaster = true

    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true

    // Aggressive shields refuse plaintext subresources outright; otherwise stay permissive
    // so pages with a few http:// images still render.
    val globalShields = com.vayunmathur.web.domain.EffectiveShields.resolve(viewModel.shields)
    settings.mixedContentMode = if (globalShields.httpsOnly) {
        WebSettings.MIXED_CONTENT_NEVER_ALLOW
    } else {
        WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }

    // Geolocation DB persists across loads
    settings.setGeolocationEnabled(true)
    try {
        @Suppress("DEPRECATION")
        settings.setGeolocationDatabasePath(webView.context.filesDir.absolutePath)
    } catch (_: Exception) {}

    settings.setSupportMultipleWindows(true)

    try {
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !viewModel.blockThirdPartyCookies)
    } catch (_: Exception) {}

    settings.userAgentString = if (viewModel.desktopMode) {
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    } else {
        WebSettings.getDefaultUserAgent(webView.context)
    }

    settings.mediaPlaybackRequiresUserGesture = false

    settings.applySystemDarkMode()

    // Safe browsing
    try {
        val compat = Class.forName("androidx.webkit.WebSettingsCompat")
        val feature = Class.forName("androidx.webkit.WebViewFeature")
        val isSupported = feature.getMethod("isFeatureSupported", String::class.java).invoke(null, "SAFE_BROWSING_ENABLE") as Boolean
        if (isSupported) {
            compat.getMethod("setSafeBrowsingEnabled", WebSettings::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, settings, true)
        }
    } catch (_: Exception) {}
}

private fun WebView.evalJsForStorageInfo(origin: String, cookieCount: Int, vm: WebViewModel) {
    evaluateJavascript(
        """(function(){
            try{
                var hasLS=false; try{hasLS=window.localStorage&&window.localStorage.length>0;}catch(e){}
                var hasIDB=!!window.indexedDB;
                var hasSW=!!navigator.serviceWorker&&!!navigator.serviceWorker.controller;
                var est=0;
                try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i); est+=(k?k.length:0)+(localStorage.getItem(k)?localStorage.getItem(k).length:0);} }catch(e){}
                return JSON.stringify({hasLS:hasLS,hasIDB:hasIDB,hasSW:hasSW,est:est});
            }catch(e){return JSON.stringify({hasLS:false,hasIDB:false,hasSW:false,est:0});}
        })();"""
    ) { json ->
        try {
            if (json == null) return@evaluateJavascript
            var s = json.trim()
            if (s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            }
            val hasLS = s.contains("\"hasLS\":true")
            val hasIDB = s.contains("\"hasIDB\":true")
            val hasSW = s.contains("\"hasSW\":true")
            val est = Regex("\"est\":(\\d+)").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            vm.updateStorageFootprint(origin, cookieCount, hasLS, hasIDB, hasSW, est)
        } catch (_: Exception) {}
    }
}

private class WebViewHolder { var webView: WebView? = null }
