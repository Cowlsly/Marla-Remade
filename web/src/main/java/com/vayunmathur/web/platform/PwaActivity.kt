package com.vayunmathur.web.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.openAppSettings
import com.vayunmathur.library.ui.rememberMultiplePermissionRequest
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.web.platform.shields.FarblingConfig
import com.vayunmathur.web.platform.shields.ShieldsEngine
import com.vayunmathur.web.platform.shields.ShieldsWebViewClient
import com.vayunmathur.web.ui.applySystemDarkMode
import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.domain.ShieldsSettings

class PwaActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "pwa_url"
        const val EXTRA_TITLE = "pwa_title"
    }

    private val initialUrlState = mutableStateOf<String?>(null)
    private val titleState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Installed sites get the same shields as the browser; loading is a no-op if the
        // engine is already up in this process.
        lifecycleScope.launch { ShieldsEngine.load(applicationContext) }
        handleIntent(intent)
        setContent {
            DynamicTheme {
                val url = initialUrlState.value
                if (url.isNullOrBlank()) {
                    Box(Modifier.fillMaxSize())
                } else {
                    PwaBrowser(
                        initialUrl = url,
                        title = titleState.value,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val urlFromExtra = intent.getStringExtra(EXTRA_URL)
        val urlFromData = intent.dataString ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        val url = urlFromExtra ?: extractHttpUrl(urlFromData ?: "") ?: return
        initialUrlState.value = url
        titleState.value = intent.getStringExtra(EXTRA_TITLE)
    }

    private fun extractHttpUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val match = Regex("https?://\\S+").find(trimmed)
            return match?.value ?: trimmed.substringBefore(" ")
        }
        Regex("https?://\\S+").find(trimmed)?.let { return it.value }
        return null
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PwaBrowser(
    initialUrl: String,
    title: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    var currentTitle by remember { mutableStateOf(title ?: "") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // For permission handling
    var pendingSysPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    var pendingGeoCallback by remember { mutableStateOf<Pair<String, GeolocationPermissions.Callback>?>(null) }

    // Geolocation grants on EITHER fine OR coarse, so this keeps the raw multi-permission
    // launcher (the shared helper reports all-granted, which would wrongly require both).
    // When both are permanently denied it still routes the user to app settings.
    fun openSettingsIfAnyPermanentlyDenied(result: Map<String, Boolean>) {
        val anyPermanentlyDenied = result.any { (perm, granted) ->
            !granted && (activity == null ||
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm))
        }
        if (anyPermanentlyDenied) openAppSettings(context)
    }

    // camera/mic/both use the shared helper, which opens app settings on permanent denial;
    // onResult(granted) drives the pending WebView request's grant()/deny().
    val cameraPermissionRequest = rememberPermissionRequest(Manifest.permission.CAMERA) { granted ->
        if (granted) {
            pendingSysPermissionRequest?.grant(pendingSysPermissionRequest?.resources ?: emptyArray())
        } else {
            pendingSysPermissionRequest?.deny()
        }
        pendingSysPermissionRequest = null
    }
    val micPermissionRequest = rememberPermissionRequest(Manifest.permission.RECORD_AUDIO) { granted ->
        if (granted) {
            pendingSysPermissionRequest?.grant(pendingSysPermissionRequest?.resources ?: emptyArray())
        } else {
            pendingSysPermissionRequest?.deny()
        }
        pendingSysPermissionRequest = null
    }
    val cameraMicPermissionRequest = rememberMultiplePermissionRequest(
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    ) { allGranted ->
        if (allGranted) {
            pendingSysPermissionRequest?.grant(pendingSysPermissionRequest?.resources ?: emptyArray())
        } else {
            pendingSysPermissionRequest?.deny()
        }
        pendingSysPermissionRequest = null
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val pair = pendingGeoCallback
        if (granted) {
            pair?.second?.invoke(pair.first, true, false)
        } else {
            pair?.second?.invoke(pair.first, false, false)
            openSettingsIfAnyPermanentlyDenied(result)
        }
        pendingGeoCallback = null
    }

    val multiDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        // deliver to pending file chooser
        @Suppress("UNCHECKED_CAST")
        (webViewRef?.tag as? android.webkit.ValueCallback<Array<Uri>>)?.onReceiveValue(
            uris.toTypedArray().takeIf { it.isNotEmpty() }
        )
        webViewRef?.tag = null
    }
    val singleDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        @Suppress("UNCHECKED_CAST")
        (webViewRef?.tag as? android.webkit.ValueCallback<Array<Uri>>)?.onReceiveValue(
            uri?.let { arrayOf(it) }
        )
        webViewRef?.tag = null
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            activity?.finish()
        }
    }

    Box(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    settings.javaScriptEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.offscreenPreRaster = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.setGeolocationEnabled(true)
                    @Suppress("DEPRECATION")
                    try {
                        settings.setGeolocationDatabasePath(ctx.filesDir.absolutePath)
                    } catch (_: Exception) {}
                    settings.setSupportMultipleWindows(true)
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.applySystemDarkMode()
                    try {
                        val compat = Class.forName("androidx.webkit.WebSettingsCompat")
                        val feature = Class.forName("androidx.webkit.WebViewFeature")
                        val isSupported = feature.getMethod("isFeatureSupported", String::class.java)
                            .invoke(null, "SAFE_BROWSING_ENABLE") as Boolean
                        if (isSupported) {
                            compat.getMethod(
                                "setSafeBrowsingEnabled",
                                WebSettings::class.java,
                                Boolean::class.javaPrimitiveType
                            ).invoke(null, settings, true)
                        }
                    } catch (_: Exception) {}

                    setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
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
                        } catch (_: Exception) {
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } catch (_: Exception) {}
                        }
                    })

                    webViewClient = object : ShieldsWebViewClient(
                        context = ctx,
                        // Installed sites have no shields panel, so they always run the
                        // aggressive preset the browser ships with.
                        shieldsFor = { EffectiveShields.resolve(ShieldsSettings.AGGRESSIVE_DEFAULTS) },
                    ) {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme ?: return false
                            if (scheme !in setOf("http", "https", "about", "data", "blob", "javascript")) {
                                return com.vayunmathur.web.platform.openExternalUri(
                                    ctx,
                                    request.url.toString(),
                                ) { fallback -> view.loadUrl(fallback) }
                            }
                            // For PWA: stay inside same origin; external origins still load but that is okay.
                            return super.shouldOverrideUrlLoading(view, request)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            CookieManager.getInstance().flush()
                            val t = view.title?.takeIf { it.isNotBlank() } ?: url ?: ""
                            if (t.isNotBlank()) currentTitle = t
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView, t: String?) {
                            if (!t.isNullOrBlank()) currentTitle = t
                        }

                        override fun onGeolocationPermissionsShowPrompt(
                            origin: String,
                            callback: GeolocationPermissions.Callback
                        ) {
                            val hasFine = ContextCompat.checkSelfPermission(
                                ctx,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            val hasCoarse = ContextCompat.checkSelfPermission(
                                ctx,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!hasFine && !hasCoarse) {
                                pendingGeoCallback = origin to callback
                                locationLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            } else {
                                callback.invoke(origin, true, false)
                            }
                        }

                        override fun onPermissionRequest(request: PermissionRequest) {
                            val resources = request.resources
                            val needsCamera = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                            val needsMic = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                            if (!needsCamera && !needsMic) {
                                request.grant(resources)
                                return
                            }
                            val hasCamera = if (needsCamera) {
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            } else true
                            val hasMic = if (needsMic) {
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            } else true
                            if (!hasCamera || !hasMic) {
                                pendingSysPermissionRequest = request
                                when {
                                    needsCamera && needsMic -> cameraMicPermissionRequest()
                                    needsCamera -> cameraPermissionRequest()
                                    needsMic -> micPermissionRequest()
                                }
                                return
                            }
                            request.grant(resources)
                        }

                        override fun onShowFileChooser(
                            webView: WebView,
                            filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                            fileChooserParams: FileChooserParams
                        ): Boolean {
                            // store callback in tag for launchers to use
                            webViewRef?.tag = filePathCallback
                            val mimeTypes = try { fileChooserParams.acceptTypes.toList() } catch (_: Exception) { emptyList<String>() }
                            val allowMultiple = try { fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE } catch (_: Exception) { false }
                            try {
                                if (allowMultiple) {
                                    multiDocLauncher.launch(
                                        mimeTypes.filter { it.isNotBlank() }.toTypedArray().takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
                                    )
                                } else {
                                    val mt = mimeTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
                                    singleDocLauncher.launch(arrayOf(mt))
                                }
                            } catch (_: Exception) {
                                filePathCallback.onReceiveValue(null)
                                webViewRef?.tag = null
                            }
                            return true
                        }
                    }

                    // Before the first load: document-start scripts do not apply retroactively.
                    (webViewClient as ShieldsWebViewClient)
                        .installFarbling(this, FarblingConfig.of(ShieldsSettings.AGGRESSIVE_DEFAULTS, emptyMap()))
                    loadUrl(initialUrl)
                }.also {
                    webViewRef = it
                }
            },
            update = { wv ->
                webViewRef = wv
                if ((wv.url ?: "") != initialUrl && wv.url.isNullOrBlank()) {
                    wv.loadUrl(initialUrl)
                }
            }
        )
    }
}
